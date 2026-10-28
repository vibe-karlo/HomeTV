// A small menu over the home screen, opened by holding OK (for now: Remove from Continue Watching).
window.Menu = (function () {
  "use strict";
  var el = null, list = null, items = [], focus = 0, open = false;

  function build() {
    el = document.createElement("section"); el.id = "menu"; el.setAttribute("aria-label", "Options");
    el.innerHTML = '<div id="menu-card"><div id="menu-title"></div><ul id="menu-list"></ul></div>';
    document.getElementById("stage").appendChild(el);
    list = el.querySelector("#menu-list");
  }
  function draw() {
    list.innerHTML = "";
    items.forEach(function (it, i) {
      var li = document.createElement("li");
      li.className = "set-row" + (i === focus ? " focus" : "");
      li.innerHTML = '<span class="label"></span>';
      li.querySelector(".label").textContent = it.label;
      li.addEventListener("click", function () { focus = i; choose(); });
      list.appendChild(li);
    });
  }
  function choose() { var it = items[focus]; close(); if (it.act) it.act(); }
  function close() { open = false; if (el) el.classList.remove("open"); }

  return {
    isOpen: function () { return open; },
    open: function (title, choices) {
      if (!el) build();
      items = choices.concat([{ label: "Cancel" }]); focus = 0;
      el.querySelector("#menu-title").textContent = title;
      draw(); open = true;
      void el.offsetWidth; el.classList.add("open");
    },
    key: function (k) {
      if (k === "ArrowUp") { focus = Math.max(0, focus - 1); draw(); }
      else if (k === "ArrowDown") { focus = Math.min(items.length - 1, focus + 1); draw(); }
      else if (k === "Enter" || k === " ") choose();
      else if (k === "Escape" || k === "Backspace" || k === "GoBack" || k === "BrowserBack" || k === "ArrowLeft") close();
      else return false;
      return true;
    },
    close: close
  };
})();
