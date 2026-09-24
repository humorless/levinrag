// HTMX does not swap 4xx/5xx responses or network failures, so without
// this a failed request just stops the spinner. Show a notice in the
// request's target instead.
(function () {
  function show(evt, message) {
    var target = evt.detail && evt.detail.target;
    if (!target) return;
    var box = document.createElement("div");
    box.className = "rounded p-3 text-sm bg-red-50 text-red-700";
    box.setAttribute("data-error", "true");
    box.textContent = message;
    target.replaceChildren(box);
  }
  document.addEventListener("htmx:responseError", function (evt) {
    var status = evt.detail.xhr ? evt.detail.xhr.status : 0;
    show(evt, status === 403 ? "頁面已過期，請重新整理後再試。" : "發生錯誤，請稍後再試。");
  });
  document.addEventListener("htmx:sendError", function (evt) {
    show(evt, "無法連線到伺服器，請稍後再試。");
  });
})();
