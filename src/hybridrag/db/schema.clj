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

; index.dtlv — collections/docs/sections/chunks (SPEC.md §6.1). NOT defined
; here: T0.3 chose Path B (docs/spikes/embedding.md) — :chunk/index-text
; needs :db/fulltext + :db.fulltext/autoDomain (no :db/embedding: the
; embedding vector lives in a separate :chunk/vec attribute, :db.type/vec,
; computed application-side). Exact 1.1.0 syntax for both confirmed by the
; T0.3/T0.4 spikes. Defined for real in Phase 1 (T1.1) once those land —
; see docs/decisions.md. index-conn opens with schema {} until then.
