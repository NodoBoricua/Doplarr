(ns doplarr.backends.readarr.impl
  (:require
   [clojure.core.async :as a]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :as flow :refer [then]]))

(def base-url (delay (str (:readarr/url @state/config) "/api/v1")))
(def api-key  (delay (:readarr/api @state/config)))

(defn GET [endpoint & [params]]
  (utils/http-request :get (str @base-url endpoint) @api-key params))

(defn POST [endpoint & [params]]
  (utils/http-request :post (str @base-url endpoint) @api-key params))

(defn PUT [endpoint & [params]]
  (utils/http-request :put (str @base-url endpoint) @api-key params))

(defn quality-profiles []
  (utils/request-and-process-body
   GET
   #(map utils/process-profile %)
   "/qualityprofile"))

(defn metadata-profiles []
  (utils/request-and-process-body
   GET
   #(map utils/process-profile %)
   "/metadataprofile"))

(defn rootfolders []
  (utils/request-and-process-body
   GET
   utils/process-rootfolders
   "/rootfolder"))

(defn get-from-book-id [book-id]
  (utils/request-and-process-body
   GET
   (comp utils/from-camel first)
   "/book/lookup"
   {:query-params {:term (str "bookId:" book-id)}}))

(defn get-from-id [id]
  (utils/request-and-process-body
   GET
   utils/from-camel
   (str "/book/" id)))

(defn execute-command [command & {:as opts}]
  (a/go
    (->> (a/<! (POST "/command" {:form-params (merge {:name command} opts)
                                 :content-type :json}))
         (then (constantly nil)))))

(defn search-book [book-id]
  (a/go
    (->> (a/<! (execute-command "BookSearch" {:bookId book-id})))
    (then (constantly nil))))

(defn status [details]
  (when-let [stats (:statistics details)]
    (when (:monitored details)
      (cond
        (> 100.0 (:percent-of-books stats)) :processing
        :else :available))))

(defn request-payload [payload]
  (-> payload
      (assoc :monitored true
             :add-options {:search-for-missing-book true})
      (dissoc :format)))
