(ns fulcro.inspect.chrome.background.main
  (:require [goog.object :as gobj]
            [cljs.core.async :as async :refer [go go-loop chan <! >! put!]]))

(defonce remote-conns* (atom {}))
(defonce tools-conns* (atom {}))

(defn handle-devtool-message [devtool-port message port]
  (cond
    (= "init" (gobj/get message "name"))
    (let [tab-id (gobj/get message "tab-id")]
      (swap! tools-conns* assoc tab-id devtool-port))

    (gobj/getValueByKeys message "fulcro-inspect-devtool-message")
    (let [tab-id      (gobj/get message "tab-id")
          remote-port (get @remote-conns* tab-id)]
      (.postMessage remote-port message))))

(defn handle-remote-message [ch message port]
  (if (gobj/getValueByKeys message "fulcro-inspect-remote-message")
    (let [tab-id (gobj/getValueByKeys port "sender" "tab" "id")]
      (put! ch {:tab-id tab-id :message message})

      ; ack message received
      (when-let [id (gobj/getValueByKeys message "__fulcro-insect-msg-id")]
        (.postMessage port #js {:ack "ok" "__fulcro-insect-msg-id" id})))))

(js/chrome.runtime.onConnect.addListener
  (fn [port]
    (case (gobj/get port "name")
      "fulcro-inspect-remote"
      (let [background->devtool-chan (chan (async/sliding-buffer 1024))
            listener                 (partial handle-remote-message background->devtool-chan)
            tab-id                   (gobj/getValueByKeys port "sender" "tab" "id")]

        (swap! remote-conns* assoc tab-id port)

        (.addListener (gobj/get port "onMessage") listener)
        (.addListener (gobj/get port "onDisconnect")
          (fn [port]
            (.removeListener port "onMessage" listener)
            (swap! remote-conns* dissoc tab-id)
            (async/close! background->devtool-chan)))

        (go-loop []
          (when-let [{:keys [tab-id message] :as data} (<! background->devtool-chan)]
            ; send message to devtool
            (if (contains? @tools-conns* tab-id)
              (do
                (.postMessage (get @tools-conns* tab-id) message)
                (recur))
              (recur)))))

      "fulcro-inspect-devtool"
      (let [listener (partial handle-devtool-message port)]
        (.addListener (gobj/get port "onMessage") listener)
        (.addListener (gobj/get port "onDisconnect")
          (fn [port]
            (.removeListener port "onMessage" listener)
            (if-let [port-key (->> @tools-conns*
                                   (keep (fn [[k v]] (if (= v port) k)))
                                   (first))]
              (swap! tools-conns* dissoc port-key)))))

      (js/console.log "Ignoring connection" (gobj/get port "name")))))