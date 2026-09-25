(ns hybridrag.db.schema
  "Datalevin schemas. See SPEC.md §6 for the authoritative definitions.")

; app.dtlv — users, tokens, traces (SPEC.md §6.2). Standard Datalevin
; scalar/ref/cardinality-many attributes only, so no spike dependency.
(def app-schema
  {:user/username {:db/valueType :db.type/string
                   :db/unique :db.unique/identity}
   :user/display-name {:db/valueType :db.type/string}
   :user/password-hash {:db/valueType :db.type/string}
   :user/groups {:db/valueType :db.type/string
                 :db/cardinality :db.cardinality/many}
   :user/admin? {:db/valueType :db.type/boolean}

   :token/hash {:db/valueType :db.type/string
                :db/unique :db.unique/identity}
   ; first 8 chars of the plaintext token, so `bb token:revoke <prefix>`
   ; can find it; not in SPEC §6.2, see docs/decisions.md
   :token/prefix {:db/valueType :db.type/string}
   :token/user {:db/valueType :db.type/ref}
   :token/label {:db/valueType :db.type/string}
   :token/created-at {:db/valueType :db.type/instant}

   :trace/id {:db/valueType :db.type/uuid
              :db/unique :db.unique/identity}
   :trace/username {:db/valueType :db.type/string}
   :trace/kind {:db/valueType :db.type/keyword}
   :trace/query {:db/valueType :db.type/string}
   :trace/at {:db/valueType :db.type/instant}
   :trace/stages {}
   :trace/answer {:db/valueType :db.type/string}
   :trace/degraded {:db/valueType :db.type/keyword
                    :db/cardinality :db.cardinality/many}})

; index.dtlv — collections/docs/sections/chunks (SPEC.md §6.1), with the
; Path B change (docs/spikes/embedding.md, docs/decisions.md): no
; :db/embedding on :chunk/index-text; the embedding lives in :chunk/vec,
; computed application-side. :chunk/vec must NOT carry :db.vec/domains
; (Datalevin 1.1.0 write-path bug) — dimensions/metric come from
; index-opts. :doc/raw-links is an addition: see docs/decisions.md.
(def index-schema
  {:collection/path {:db/valueType :db.type/string
                     :db/unique :db.unique/identity}
   :collection/name {:db/valueType :db.type/string}
   :collection/parent {:db/valueType :db.type/ref}
   :collection/declared-groups {:db/valueType :db.type/string
                                :db/cardinality :db.cardinality/many}
   :collection/effective-groups {:db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/many}

   :doc/path {:db/valueType :db.type/string
              :db/unique :db.unique/identity}
   :doc/title {:db/valueType :db.type/string}
   :doc/collection {:db/valueType :db.type/ref}
   :doc/hash {:db/valueType :db.type/string}
   ; sha256 of the file without its frontmatter read_groups line: equal
   ; content hashes mean only the ACL changed (SPEC §7.2 rule 3)
   :doc/content-hash {:db/valueType :db.type/string}
   :doc/tags {:db/valueType :db.type/string
              :db/cardinality :db.cardinality/many}
   :doc/declared-groups {:db/valueType :db.type/string
                         :db/cardinality :db.cardinality/many}
   :doc/effective-groups {:db/valueType :db.type/string
                          :db/cardinality :db.cardinality/many}
   :doc/links-to {:db/valueType :db.type/ref
                  :db/cardinality :db.cardinality/many}
   :doc/raw-links {}
   :doc/frontmatter {}
   :doc/ingested-at {:db/valueType :db.type/instant}

   :section/id {:db/valueType :db.type/string
                :db/unique :db.unique/identity}
   :section/doc {:db/valueType :db.type/ref}
   :section/parent {:db/valueType :db.type/ref}
   :section/heading {:db/valueType :db.type/string}
   :section/level {:db/valueType :db.type/long}
   :section/trail {:db/valueType :db.type/string}

   :chunk/id {:db/valueType :db.type/string
              :db/unique :db.unique/identity}
   :chunk/doc {:db/valueType :db.type/ref}
   :chunk/section {:db/valueType :db.type/ref}
   :chunk/ordinal {:db/valueType :db.type/long}
   :chunk/text {:db/valueType :db.type/string}
   :chunk/index-text {:db/valueType :db.type/string
                      :db/fulltext true
                      :db.fulltext/autoDomain true}
   :chunk/vec {:db/valueType :db.type/vec}
   :chunk/tokens {:db/valueType :db.type/long}
   :chunk/hard-cut? {:db/valueType :db.type/boolean}
   :chunk/char-start {:db/valueType :db.type/long}
   :chunk/char-end {:db/valueType :db.type/long}})

(defn index-opts
  "Store options for index.dtlv. Must be identical on every open."
  [dims]
  {:vector-opts {:dimensions dims
                 :metric-type :cosine}})
