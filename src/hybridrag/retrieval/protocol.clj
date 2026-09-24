(ns hybridrag.retrieval.protocol
  "Storage-independent retrieval interface (SPEC.md §9.2, D4).

   ACL is enforced INSIDE every implementation (§9.3): nothing that leaves
   a Retriever may belong to a doc the principal cannot read. Fusion,
   rerank and context packing live above this protocol and never re-check
   ACL.")

(defprotocol Retriever
  (index-doc! [this parsed-doc] "Upsert one built doc with its sections and chunks.")
  (delete-doc! [this doc-path] "Remove a doc and all its sections and chunks.")
  (channel [this principal channel-kw query opts]
    "ACL-filtered recall for :lexical or :semantic. Returns
     {:candidates [{:chunk/id :doc/path :rank n :score x} ...]  ; best-first, ≤ channel-k
      :extended   [...]   ; same shape: every ACL-passing over-fetch hit
      :raw-hits n :after-acl m :starved? bool}")
  (neighbors [this principal chunk-id opts]
    "ACL-filtered chunks of the same section within ±(:radius opts, 1) ordinals.")
  (linked-docs [this principal doc-paths]
    "ACL-filtered doc paths 1 hop from `doc-paths` via :doc/links-to, both directions.")
  (chunks [this principal chunk-ids]
    "ACL-filtered chunk maps for `chunk-ids`, in input order; unknown or
     unreadable ids are dropped."))
