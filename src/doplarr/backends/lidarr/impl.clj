(ns doplarr.backends.lidarr.impl
  (:require
   [clojure.core.async :as a]
   [clojure.set :as set]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :as flow :refer [then]]
   [taoensso.timbre :refer [warn]]))

(def base-url (delay (str (:lidarr/url @state/config) "/api/v1")))
(def api-key  (delay (:lidarr/api @state/config)))

(defn GET [endpoint & [params]]
  (warn "🔍 Lidarr GET request:" (str @base-url endpoint) "with params:" params)
  (utils/http-request :get (str @base-url endpoint) @api-key params))

(defn POST [endpoint & [params]]
  (warn "🔍 Lidarr POST request:" (str @base-url endpoint) "with payload:" params)
  (utils/http-request :post (str @base-url endpoint) @api-key params))

(defn PUT [endpoint & [params]]
  (warn "🔍 Lidarr PUT request:" (str @base-url endpoint) "with payload:" params)
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

(defn- process-image [image]
  (when-let [url (:remote-url image)]
    {:remote-poster url}))

(defn- images-details [details]
  (when-let [images (:images details)]
    (->> images
         (map #(-> %
                   (utils/from-camel)
                   (select-keys [:cover-type :remote-url])))
         (filter #(= "cover" (:cover-type %)))
         first
         process-image)))

(defn get-from-musicbrainz [musicbrainz-id]
  (utils/request-and-process-body
   GET
   (fn [resp]
     (warn "Artist details from API:" resp)
     (let [details (utils/from-camel (first resp))]
       (merge details (images-details details))))
   "/artist/lookup"
   {:query-params {:term (str "mbid:" musicbrainz-id)}}))

(defn get-from-id [id]
  (utils/request-and-process-body
   GET
   (fn [resp]
     (warn "Artist details from API:" resp)
     (let [details (utils/from-camel resp)]
       (merge details (images-details details))))
   (str "/artist/" id)))

(defn get-albums [artist-id]
  (utils/request-and-process-body
   GET
   (partial mapv #(let [details (utils/from-camel %)]
                     (merge details (images-details details))))
   "/album"
   {:query-params {:artistId artist-id}}))

(defn get-albums-by-mbid [musicbrainz-id]
  (utils/request-and-process-body
   GET
   (partial mapv #(let [details (utils/from-camel %)]
                     (merge details (images-details details))))
   "/album/lookup"
   {:query-params {:term (str "mbId:" musicbrainz-id)}}))

(defn get-albums-by-name [artist-name]
  (utils/request-and-process-body
   GET
   (partial mapv #(let [details (utils/from-camel %)]
                     (merge details (images-details details))))
   "/album/lookup"
   {:query-params {:term artist-name}}))


(defn execute-command [command & {:as opts}]
  (a/go
    (->> (a/<! (POST "/command" {:form-params (merge {:name command} opts)
                                 :content-type :json}))
         (then (constantly nil)))))

(defn search-artist [artist-id]
  (a/go
    (->> (a/<! (execute-command "ArtistSearch" {:artistId artist-id})))
    (then (constantly nil))))

(defn status [details album-id]
  (a/go
    (when-not (= -1 album-id)
      (let [albums (a/<! (if (:id details)
                           (get-albums (:id details))
                           (get-albums-by-mbid (:foreign-artist-id details))))
            _ (warn "Lidarr status albums fetched"
                    {:artist-id (:id details)
                     :foreign-artist-id (:foreign-artist-id details)
                     :album-count (count albums)})
            album (first (filter #(= album-id (:id %)) albums))]
        (when (:monitored album)
          :available)))))

(defn request-payload [payload details]
  (a/go
    (let [all-albums (a/<! (if (:id details)
                              (get-albums (:id details))
                              (get-albums-by-mbid (:foreign-artist-id details))))
          _ (warn "Lidarr request-payload albums fetched"
                  {:artist-id (:id details)
                   :foreign-artist-id (:foreign-artist-id details)
                   :album-count (count all-albums)})
          selecting-all? (= -1 (:album payload))
          selected-albums (when-not selecting-all?
                            (let [album-id (:album payload)]
                              (if (some #(= (:id %) album-id) all-albums)
                                ;; Álbum con ID real (en Lidarr)
                                (map #(assoc % :monitored (= (:id %) album-id)) all-albums)
                                ;; Álbum externo - usar índice y solo campos válidos
                                (map-indexed
                                 (fn [idx album]
                                   {:title (:title album)
                                    :monitored (= idx album-id)})
                                 all-albums))))
          add-options (if selecting-all?
                        {:monitor "all"
                         :search-for-missing-albums true}
                        {:monitor "existing" ;;pendiente a este cambio
                         :search-for-missing-albums true})]
      (warn "Selected albums for payload:" selected-albums)
      (-> payload
          (assoc :monitored true
                 :add-options add-options)
          (#(if selecting-all?
              (dissoc % :albums)
              (assoc % :albums selected-albums)))
          (dissoc :album :format)
          (set/rename-keys {:title :artist-name}))))
)