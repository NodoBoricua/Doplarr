(ns doplarr.backends.readarr
  (:require
   [clojure.core.async :as a]
   [doplarr.backends.readarr.impl :as impl]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :refer [then]]
   [taoensso.timbre :refer [warn]]))

(defn search [term _]
  (letfn [(process-search-result [result]
            (let [book (utils/from-camel result)]
              (select-keys book [:title :year :id :foreign-book-id])))]
    (utils/request-and-process-body
     impl/GET
     #(mapv process-search-result %)
     "/book/lookup"
     {:query-params {:term term}})))

(defn additional-options [_ _]
  (a/go
    (let [quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))
          rootfolders (a/<! (impl/rootfolders))
          {:keys [readarr/quality-profile readarr/metadata-profile readarr/rootfolder]} @state/config
          default-profile-id (utils/id-from-name quality-profiles quality-profile)
          default-metadata-id (utils/id-from-name metadata-profiles metadata-profile)
          default-root-folder (utils/id-from-name rootfolders rootfolder)]
      (when (and quality-profile (nil? default-profile-id))
        (warn "Default quality profile in config doesn't exist in backend, check spelling"))
      (when (and metadata-profile (nil? default-metadata-id))
        (warn "Default metadata profile in config doesn't exist in backend, check spelling"))
      (when (and rootfolder (nil? default-root-folder))
        (warn "Default root folder in config doesn't exist in backend, check spelling"))
      {:quality-profile-id
       (cond
         default-profile-id default-profile-id
         (= 1 (count quality-profiles)) (:id (first quality-profiles))
         :else quality-profiles)
       :metadata-profile-id
       (cond
         default-metadata-id default-metadata-id
         (= 1 (count metadata-profiles)) (:id (first metadata-profiles))
         :else metadata-profiles)
       :rootfolder-id
       (cond
         default-root-folder default-root-folder
         (= 1 (count rootfolders)) (:id (first rootfolders))
         :else rootfolders)})))

(defn request-embed [{:keys [title quality-profile-id metadata-profile-id foreign-book-id rootfolder-id]} _]
  (a/go
    (let [rootfolders (a/<! (impl/rootfolders))
          quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))
          details (a/<! (impl/get-from-book-id foreign-book-id))]
      {:title title
       :overview (:overview details)
       :poster (:remote-poster details)
       :media-type :book
       :request-formats [""]
       :rootfolder (utils/name-from-id rootfolders rootfolder-id)
       :quality-profile (utils/name-from-id quality-profiles quality-profile-id)
       :metadata-profile (utils/name-from-id metadata-profiles metadata-profile-id)})))

(defn request [payload _]
  (a/go
    (let [status (impl/status (a/<! (impl/get-from-book-id (:foreign-book-id payload))))
          rfs (a/<! (impl/rootfolders))
          payload (assoc payload :root-folder-path (utils/name-from-id rfs (:rootfolder-id payload)))]
      (if status
        status
        (->> (a/<! (impl/POST "/book" {:form-params (utils/to-camel (impl/request-payload payload))
                                        :content-type :json}))
             (then (constantly nil)))))))
