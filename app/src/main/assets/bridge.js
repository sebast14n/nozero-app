/* No Zero — shim bridge injectat la document-start (INAINTE de scripturile modulului web).
   Construieste window.NZBridge (API cu callback-uri, cum il asteapta modulele) peste interfata
   nativa raw window.NZAndroid (@JavascriptInterface). In browser pur (fara NZAndroid) → undefined,
   deci modulul cade pe API-urile browser. Bridge v1: location + identity + module-loader.
   (liveSpecies = INTENTIONAT neimplementat in Faza 1; vine in Faza 3 cu BirdNET nativ.)

   ROBUSTETE: window.NZBridge e un GETTER lazy — se rezolva la momentul ACCESULUI de catre modul,
   daca NZAndroid e prezent atunci. Asta evita dependenta fragila de ordinea exacta
   addJavascriptInterface vs document-start pe diverse WebView-uri / OEM-uri. */
(function () {
  function build() {
    var watchers = [], onceQ = [];
    window.__nzLoc = function (lat, lon, acc) {
      watchers.forEach(function (cb) { try { cb({ lat: lat, lon: lon, acc: acc }); } catch (e) {} });
    };
    window.__nzLocOnce = function (lat, lon, acc, err) {
      var q = onceQ.splice(0);
      q.forEach(function (p) {
        try { if (err) { p.err && p.err(err); } else { p.ok && p.ok({ lat: lat, lon: lon, acc: acc }); } } catch (e) {}
      });
    };
    return {
      native: true,
      watchLocation: function (cb) { if (typeof cb === 'function') watchers.push(cb); window.NZAndroid.startLocation(); },
      stopLocation: function () { watchers = []; window.NZAndroid.stopLocation(); },
      getLocation: function (ok, err) { onceQ.push({ ok: ok, err: err }); window.NZAndroid.getLocationOnce(); },
      getIdentity: function () { try { return JSON.parse(window.NZAndroid.getIdentity()); } catch (e) { return { native: true }; } },
      loadModule: function (name) { window.NZAndroid.loadModule(String(name)); },
      readCells: function () { try { return JSON.parse(window.NZAndroid.readCells()); } catch (e) { return { error: String(e) }; } },
      log: function (m) { try { window.NZAndroid.log(String(m)); } catch (e) {} }
    };
  }

  var instance = null;
  try {
    Object.defineProperty(window, 'NZBridge', {
      configurable: true,
      get: function () {
        if (!window.NZAndroid) return undefined;   // browser pur → modulul foloseste API-urile browser
        if (!instance) instance = build();
        return instance;
      }
    });
  } catch (e) {
    if (window.NZAndroid) window.NZBridge = build();
  }
})();
