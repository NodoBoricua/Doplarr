(ns doplarr.backends.lidarr
  (:require
   [clojure.core.async :as a]
   [clojure.set :as set]
   [clojure.string :as string]
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
          _ (warn "🔍 Lidarr additional-options - checking result:"
                  {:has-id (boolean (:id result))
                   :id-value (:id result)
                   :foreign-artist-id (:foreign-artist-id result)
                   :title (:title result)})
          albums-by-id (when (:id result)
                         (do (warn "📀 Trying get-albums by artist ID:" (:id result))
                             (a/<! (impl/get-albums (:id result)))))
          albums (if (and albums-by-id (seq albums-by-id))
                   albums-by-id
                   (do (warn "🎵 No albums found by ID, trying multiple lookup methods...")
                       (let [mbid-albums (a/<! (impl/get-albums-by-mbid (:foreign-artist-id result)))
                             name-albums (a/<! (impl/get-albums-by-name (:title result)))]
                         (warn "🔍 Lookup results:" {:mbid-count (count mbid-albums) :name-count (count name-albums)})
                         (if (seq mbid-albums)
                           mbid-albums
                           name-albums))))
          _ (warn "🎧 Lidarr additional-options albums fetched"
                  {:artist-id (:id result)
                   :foreign-artist-id (:foreign-artist-id result)
                   :album-count (count albums)
                   :first-album (when (seq albums) (select-keys (first albums) [:id :title]))})
          ;; Usar índice como ID temporal en álbumes externos
          album-options (->> albums
                             (map-indexed (fn [idx album]
                                            (if (:id album)
                                              {:id (:id album) :name (:title album)}
                                              {:id idx :name (str "[External] " (:title album)) :external true})))
                             (take 25))
          _ (warn "🎵 Album options created:" {:total-albums (count albums) :options-created (count album-options)})
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
          albums (a/<! (if (:id details)
                         (impl/get-albums (:id details))
                         (impl/get-albums-by-mbid foreign-artist-id)))
          _ (warn "Lidarr request-embed albums fetched"
                  {:artist-id (:id details)
                   :foreign-artist-id foreign-artist-id
                   :album-count (count albums)})
          album-details (when-not (= -1 album)
                          (or
                            (some #(when (= album (:id %)) %) albums)
                            (nth albums album nil)))]
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
    (let [details (if-let [id (:id payload)]
                    ;; Usamos el payload directo si ya tiene ID
                    {:id id :foreign-artist-id (:foreign-artist-id payload)}
                    ;; Sino, lo buscamos en MusicBrainz
                    (a/<! (impl/get-from-musicbrainz (:foreign-artist-id payload))))
          albums (a/<! (if (:id details)
                         (impl/get-albums (:id details))
                         (impl/get-albums-by-mbid (:foreign-artist-id payload))))
          album-id (:album payload)
          selected-album (cond
                           (some #(= (:id %) album-id) albums)
                           (first (filter #(= (:id %) album-id) albums))

                           (and (number? album-id) (< album-id (count albums)))
                           (nth albums album-id nil))
          status (a/<! (impl/status details (:id selected-album)))]
      
      (if status
        status
        (if-let [id (:id payload)]
          ;; Artista ya existe en Lidarr: actualizar álbum específico
          (if (or (nil? selected-album) (nil? (:id selected-album)))
            (warn "Cannot update specific album for external album, adding artist instead")
            (do
              (warn "Updating existing album:" selected-album)
              (let [updated-album (assoc selected-album :monitored true)]
                (impl/PUT (str "/album/" (:id updated-album))
                          {:form-params (utils/to-camel updated-album)
                           :content-type :json}))))
          ;; Artista nuevo: agregar a Lidarr
          (let [rfs (a/<! (impl/rootfolders))
                payload-with-path (assoc payload :root-folder-path (utils/name-from-id rfs (:rootfolder-id payload)))
                request-payload (a/<! (impl/request-payload payload-with-path details))]
            (warn "Final payload to Lidarr POST:" request-payload)
            (->> (a/<! (impl/POST "/artist"
                                  {:form-params (utils/to-camel request-payload)
                                   :content-type :json}))
                 (then (fn [_]
                         (when-let [id (:id payload)]
                           (impl/search-artist id)))))))))))

