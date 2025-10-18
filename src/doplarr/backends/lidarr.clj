(ns doplarr.backends.lidarr
  (:require
   [clojure.core.async :as a]
   [clojure.set :as set]
   [doplarr.backends.lidarr.impl :as impl]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :refer [then]]
   [taoensso.timbre :refer [warn]]))

(defn search [term _]
  (letfn [(process-search-result [result]
            (-> result
                (utils/from-camel)
                (set/rename-keys {:artist-name :title})
                (select-keys [:title :id :foreign-artist-id])))]
    (utils/request-and-process-body
     impl/GET
     #(mapv process-search-result %)
     "/artist/lookup"
     {:query-params {:term term}})))

(defn additional-options [result _]
  (a/go
    (let [quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))
          rootfolders (a/<! (impl/rootfolders))
          albums (a/<! (impl/get-albums (:id result)))
          album-options (map #(hash-map :id (:id %) :name (:title %)) albums)
          {:keys [lidarr/metadata-profile
                  lidarr/quality-profile
                  lidarr/album-folders
                  lidarr/rootfolder]} @state/config
          default-profile-id (utils/id-from-name quality-profiles quality-profile)
          default-metadata-id (utils/id-from-name metadata-profiles metadata-profile)
          default-root-folder (utils/id-from-name rootfolders rootfolder)]
      (when (and quality-profile (nil? default-profile-id))
        (warn "Default quality profile in config doesn't exist in backend, check spelling"))
      (when (and metadata-profile (nil? default-metadata-id))
        (warn "Default metadata profile in config doesn't exist in backend, check spelling"))
      (when (and rootfolder (nil? default-root-folder))
        (warn "Default root folder in config doesn't exist in backend, check spelling"))
      {:album-folders (if (nil? album-folders) false album-folders)
       :album (cond
                (= 1 (count albums)) (:id (first albums))
                :else (conj album-options {:name "All Albums" :id -1}))
       :quality-profile-id (cond
                             quality-profile default-profile-id
                             (= 1 (count quality-profiles)) (:id (first quality-profiles))
                             :else quality-profiles)
       :metadata-profile-id (cond
                              default-metadata-id default-metadata-id
                              (= 1 (count metadata-profiles)) (:id (first metadata-profiles))
                              :else metadata-profiles)
       :rootfolder-id (cond
                        default-root-folder default-root-folder
                        (= 1 (count rootfolders)) (:id (first rootfolders))
                        :else rootfolders)})))

(defn request-embed [{:keys [title quality-profile-id metadata-profile-id foreign-artist-id rootfolder-id album]} _]
  (a/go
    (let [rootfolders (a/<! (impl/rootfolders))
          quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))
          details (a/<! (impl/get-from-musicbrainz foreign-artist-id))
          albums (a/<! (impl/get-albums (:id details)))
          album-details (when-not (= -1 album)
                          (first (filter #(= album (:id %)) albums)))]

      (warn "ARTIST DETAILS DEBUG:" details)

      {:title title
       :overview (let [overview (:overview details)]
                   (when overview
                     (if (>= (count overview) 95)
                       (subs overview 0 95)
                       overview)))
       :poster (or (:remote-poster album-details) (:remote-poster details))
       :media-type :music
       :album (utils/name-from-id albums album)
       :request-formats [""]
       :quality-profile (:name (first (filter #(= quality-profile-id (:id %)) quality-profiles)))
       :metadata-profile (:name (first (filter #(= metadata-profile-id (:id %)) metadata-profiles)))
       :rootfolder (utils/name-from-id rootfolders rootfolder-id)})))

(defn request [payload _]
  (a/go
    (let [details (a/<! (if-let [id (:id payload)]
                          (impl/get-from-id id)
                          (impl/get-from-musicbrainz (:foreign-artist-id payload))))
          status (a/<! (impl/status details (:album payload)))]
      (if status
        status
        (if-let [id (:id payload)]
          (let [albums (a/<! (impl/get-albums id))
                album (-> (filter #(= (:id %) (:album payload)) albums)
                          first
                          (assoc :monitored true))]
            (warn "Updating album with payload:" album)
            ;; 👇 Este PUT solo sirve si el álbum ya existe
            (impl/PUT (str "/album/" (:id album)) {:form-params (utils/to-camel album)
                                                   :content-type :json}))
          (let [rfs (a/<! (impl/rootfolders))
                payload (assoc payload :root-folder-path (utils/name-from-id rfs (:rootfolder-id payload)))
                request-payload (a/<! (impl/request-payload payload details))]
            (warn "Final payload to Lidarr POST:" request-payload)
            (->> (a/<! (impl/POST "/artist" {:form-params (utils/to-camel request-payload)
                                             :content-type :json}))
                 (then (fn [_]
                         (when-let [id (:id payload)]
                           (impl/search-artist id)))))))))))
