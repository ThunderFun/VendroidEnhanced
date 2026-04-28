!(() => {
    var _vendroidCapturedWreq = null;
    var _vendroidJsonpCallback = null;
    var _vendroidChunkArr = null;
    var _vendroidCaptureAttempts = 0;

    (function setupWebpackInterception() {
        var existing = window.webpackChunkdiscord_app;
        if (existing && Array.isArray(existing)) {
            _vendroidChunkArr = existing;
            hookPushProperty(existing);
        }
        try {
            Object.defineProperty(window, 'webpackChunkdiscord_app', {
                configurable: true,
                enumerable: true,
                get() { return _vendroidChunkArr; },
                set(arr) {
                    _vendroidChunkArr = arr;
                    if (Array.isArray(arr)) {
                        hookPushProperty(arr);
                    }
                }
            });
        } catch(e) {
            console.error("[Vendroid] webpack interception setup failed: " + e.message);
        }
    })();

    function hookPushProperty(arr) {
        try {
            var currentPush = arr.push;
            var overrideCount = 0;
            Object.defineProperty(arr, 'push', {
                get() { return currentPush; },
                set(fn) {
                    overrideCount++;
                    currentPush = fn;
                    if (typeof fn === 'function' && !_vendroidJsonpCallback) {
                        _vendroidJsonpCallback = fn;
                        console.log("[Vendroid] Captured push override #" + overrideCount + ": " + fn.toString().substring(0, 80));
                        setTimeout(vendroidTryCaptureWreq, 50);
                    }
                },
                configurable: true,
                enumerable: false
            });
        } catch(e) {
            console.error("[Vendroid] push hook failed: " + e.message);
        }
    }

    function vendroidTryCaptureWreq() {
        if (_vendroidCapturedWreq) return;
        if (!_vendroidJsonpCallback) return;

        _vendroidCaptureAttempts++;
        var captured = null;
        var fakeModuleId = "_vc_wreq_" + Date.now();
        var fakeFactory = function(module, exports, __webpack_require__) {
            captured = __webpack_require__;
        };

        var fakeChunk = [[0], {}];
        fakeChunk[1][fakeModuleId] = fakeFactory;

        try {
            _vendroidJsonpCallback(fakeChunk);
        } catch(e) {
            if (_vendroidCaptureAttempts <= 3) console.log("[Vendroid] Fake chunk error: " + e.message);
        }

        if (captured && typeof captured === "function" && captured.c) {
            _vendroidCapturedWreq = captured;
            console.log("[Vendroid] Captured __webpack_require__ from fake chunk!");
            vendroidCallInitWebpack();
        } else if (captured && typeof captured === "object") {
            var found = null;
            try {
                var keys = Object.keys(captured);
                for (var i = 0; i < keys.length; i++) {
                    var val = captured[keys[i]];
                    if (typeof val === "function" && val.c) { found = val; break; }
                }
            } catch(e) {}
            if (found) {
                _vendroidCapturedWreq = found;
                console.log("[Vendroid] Captured __webpack_require__ from object wrapper!");
                vendroidCallInitWebpack();
            } else {
                if (_vendroidCaptureAttempts <= 3) console.log("[Vendroid] Fake chunk captured object but no wreq inside (keys=" + (captured ? Object.keys(captured).length : 0) + ")");
                if (!_vendroidCapturedWreq) {
                    setTimeout(vendroidTryCaptureWreq, 500);
                }
            }
        } else {
            if (_vendroidCaptureAttempts <= 3) console.log("[Vendroid] Fake chunk did not capture __webpack_require__ (type=" + typeof captured + ", hasC=" + !!(captured && captured.c) + ")");
            if (!_vendroidCapturedWreq) {
                setTimeout(vendroidTryCaptureWreq, 500);
            }
        }
    }

    function vendroidCallInitWebpack() {
        if (!_vendroidCapturedWreq) return;
        function doCall() {
            try {
                if (typeof Vencord !== "undefined" && Vencord.Webpack) {
                    if (Vencord.Webpack.wreq) {
                        console.log("[Vendroid] wreq already set, skipping _initWebpack");
                        return;
                    }
                    if (typeof Vencord.Webpack._initWebpack === "function") {
                        Vencord.Webpack._initWebpack(_vendroidCapturedWreq);
                        console.log("[Vendroid] _initWebpack called! wreq=" + typeof Vencord.Webpack.wreq + " cache=" + typeof Vencord.Webpack.cache);
                        // Immediately try to advance to plugins stage — no need to wait for the poll
                        tryAdvanceInit();
                        return;
                    }
                } else {
                    setTimeout(doCall, 50);
                }
            } catch(e) {
                console.error("[Vendroid] _initWebpack call failed: " + e.message);
            }
        }
        doCall();
    }

    function tryPushFakeChunkDirectly() {
        if (_vendroidCapturedWreq) return true;
        var chunkArr = window.webpackChunkdiscord_app || _vendroidChunkArr;
        if (!chunkArr) return false;
        var pushFn = chunkArr.push;
        if (typeof pushFn !== "function") return false;
        if (pushFn.toString().indexOf("[native code]") !== -1) return false;

        var captured = null;
        var fakeModuleId = "_vc_wreq_" + Date.now();
        var fakeFactory = function(module, exports, __webpack_require__) {
            captured = __webpack_require__;
        };
        var fakeChunk = [[0], {}];
        fakeChunk[1][fakeModuleId] = fakeFactory;
        try {
            pushFn.call(chunkArr, fakeChunk);
        } catch(e) {}
        if (captured && typeof captured === "function" && captured.c) {
            _vendroidCapturedWreq = captured;
            console.log("[Vendroid] Captured __webpack_require__ via direct push!");
            vendroidCallInitWebpack();
            return true;
        }
        return false;
    }

    console.log("[Vendroid] vencord_mobile.js loaded");
    console.log("[Vendroid] Early state: chunkArr=" + (window.webpackChunkdiscord_app ? "exists len=" + window.webpackChunkdiscord_app.length + " push=" + window.webpackChunkdiscord_app.push.toString().substring(0, 80) : "MISSING"));

    function getModalEscapeHandler() {
        try {
            if (typeof Vencord !== "undefined" && Vencord.Webpack && typeof Vencord.Webpack.findLazy === "function") {
                return Vencord.Webpack.findLazy(m => m.binds?.length === 1 && m.binds[0] === "esc");
            }
        } catch(e) {
            console.error("[Vendroid] getModalEscapeHandler error: " + e.message);
        }
        return null;
    }

    let ModalEscapeHandler = null;
    try {
        ModalEscapeHandler = getModalEscapeHandler();
        if (ModalEscapeHandler) {
            console.log("[Vendroid] ModalEscapeHandler found");
        } else {
            console.log("[Vendroid] ModalEscapeHandler not ready yet");
        }
    } catch(e) {
        console.error("[Vendroid] ModalEscapeHandler FAILED: " + e.message);
    }

    let isSidebarOpen = false;
    try {
        var path = window.location.pathname;
        // Sidebar is typically showing (channel/DM list) when not inside a specific channel view.
        isSidebarOpen = !/^\/channels\/[^\/]+\/[^\/]+$/.test(path);
    } catch(e) {}
    let initialized = false;

    function recoverPlugins() {
        try {
            const plugins = Vencord.Plugins.plugins;
            const total = Object.keys(plugins).length;
            const enabled = Object.values(plugins).filter(p => Vencord.Plugins.isPluginEnabled(p.name)).length;
            console.log("[Vendroid] Plugin state: " + enabled + "/" + total + " enabled");

            const disabledRequired = Object.values(plugins).filter(p =>
                (p.required || p.enabledByDefault) && !Vencord.Plugins.isPluginEnabled(p.name)
            );
            console.log("[Vendroid] " + disabledRequired.length + " required/default plugins are disabled");
            let successCount = 0;
            if (disabledRequired.length > 0) {
                for (const p of disabledRequired) {
                    try {
                        if (!Vencord.Settings.plugins[p.name]) {
                            Vencord.Settings.plugins[p.name] = { enabled: true };
                        } else {
                            Vencord.Settings.plugins[p.name].enabled = true;
                        }
                        if (!Vencord.Plugins.pluginRequiresRestart(p) && !p.started) {
                            try { Vencord.Plugins.startDependenciesRecursive(p); } catch(e) {}
                            Vencord.Plugins.startPlugin(p);
                        }
                        console.log("[Vendroid] Enabled: " + p.name + " (now=" + Vencord.Plugins.isPluginEnabled(p.name) + ",started=" + p.started + ")");
                        successCount++;
                    } catch(e) {
                        console.error("[Vendroid] Failed to enable " + p.name + ": " + e.message);
                    }
                }
            }
            console.log("[Vendroid] Recovery result: " + successCount + "/" + disabledRequired.length + " enabled");
            return disabledRequired.length - successCount;
        } catch(e) {
            console.error("[Vendroid] recoverPlugins error: " + e.message);
            return -1;
        }
    }

    function tryStartPluginsStage() {
        try {
            var stages = {};
            Object.values(Vencord.Plugins.plugins).forEach(function(p) {
                if (p.startAt !== undefined) {
                    stages[p.startAt] = (stages[p.startAt] || 0) + 1;
                }
            });
            console.log("[Vendroid] Plugin startAt distribution: " + JSON.stringify(stages));

            var keys = Object.keys(stages);
            for (var i = 0; i < keys.length; i++) {
                try {
                    Vencord.Plugins.startAllPlugins(keys[i]);
                    console.log("[Vendroid] Called startAllPlugins(" + JSON.stringify(keys[i]) + ")");
                } catch(e) {
                    console.error("[Vendroid] startAllPlugins(" + JSON.stringify(keys[i]) + ") failed: " + e.message);
                }
            }
        } catch(e) {
            console.error("[Vendroid] tryStartPluginsStage error: " + e.message);
        }
    }

    function extractWebpackRequire() {
        if (_vendroidCapturedWreq) return _vendroidCapturedWreq;

        if (_vendroidJsonpCallback) {
            vendroidTryCaptureWreq();
            if (_vendroidCapturedWreq) return _vendroidCapturedWreq;
        }

        if (tryPushFakeChunkDirectly()) {
            return _vendroidCapturedWreq;
        }

        try {
            var searchTargets = [window, self];
            for (var t = 0; t < searchTargets.length; t++) {
                var obj = searchTargets[t];
                var keys;
                try { keys = Object.getOwnPropertyNames(obj); } catch(e) { continue; }
                for (var k = 0; k < keys.length && k < 200; k++) {
                    try {
                        var val = obj[keys[k]];
                        if (typeof val === "function" && val.c && typeof val.c === "object" &&
                            (val.m !== undefined || val.d !== undefined)) {
                            console.log("[Vendroid] Found __webpack_require__ candidate at " + keys[k]);
                            return val;
                        }
                    } catch(e) {}
                }
            }
        } catch(e) {}

        try {
            var chunkArr = window.webpackChunkdiscord_app || _vendroidChunkArr;
            if (chunkArr && chunkArr.length > 0) {
                for (var i = 0; i < chunkArr.length && i < 5; i++) {
                    var entry = chunkArr[i];
                    if (!Array.isArray(entry) || entry.length < 2) continue;
                    var modules = entry[1];
                    if (!modules || typeof modules !== "object") continue;
                    var mkeys = Object.keys(modules);
                    for (var j = 0; j < mkeys.length && j < 5; j++) {
                        var factory = modules[mkeys[j]];
                        if (typeof factory !== "function") continue;
                        try {
                            var captured = null;
                            var fakeModule = { exports: {}, id: mkeys[j], loaded: false };
                            factory(fakeModule, fakeModule.exports, function(modId) {
                                if (!captured && typeof modId === "function" && modId.c) {
                                    captured = modId;
                                }
                                return {};
                            });
                            if (captured && typeof captured === "function" && captured.c) {
                                console.log("[Vendroid] Extracted __webpack_require__ from chunk " + i + " module " + mkeys[j]);
                                return captured;
                            }
                        } catch(e) {}
                    }
                }
            }
        } catch(e) {
            console.error("[Vendroid] extractWebpackRequire chunk scan error: " + e.message);
        }
        return null;
    }

    function tryInitWebpack() {
        if (Vencord.Webpack.wreq) {
            console.log("[Vendroid] wreq already set, skipping manual init");
            return true;
        }

        if (_vendroidCapturedWreq) {
            try {
                if (typeof Vencord.Webpack._initWebpack === "function") {
                    Vencord.Webpack._initWebpack(_vendroidCapturedWreq);
                    console.log("[Vendroid] _initWebpack called from captured wreq, wreq=" + typeof Vencord.Webpack.wreq);
                    if (Vencord.Webpack.wreq) return true;
                }
            } catch(e) {
                console.error("[Vendroid] _initWebpack from captured wreq failed: " + e.message);
            }
        }

        var wreq = extractWebpackRequire();
        if (wreq && typeof Vencord.Webpack._initWebpack === "function") {
            try {
                Vencord.Webpack._initWebpack(wreq);
                console.log("[Vendroid] _initWebpack called, wreq=" + typeof Vencord.Webpack.wreq + " cache=" + typeof Vencord.Webpack.cache);
                if (Vencord.Webpack.wreq) return true;
            } catch(e) {
                console.error("[Vendroid] _initWebpack failed: " + e.message);
            }
        }
        return false;
    }

    let cachedFluxDispatcher = null;

    function findFluxDispatcher() {
        if (cachedFluxDispatcher) return cachedFluxDispatcher;
        try {
            var fd = Vencord.Webpack.Common?.FluxDispatcher;
            if (fd && typeof fd === "object" && fd.dispatch && fd.subscribe) { cachedFluxDispatcher = fd; return fd; }
        } catch(e) {}
        try {
            var fd2 = Vencord.Webpack.findByProps("dispatch", "subscribe");
            if (fd2 && typeof fd2 === "object" && fd2.subscribe) { cachedFluxDispatcher = fd2; return fd2; }
        } catch(e) {}
        try {
            var fd3 = Vencord.Webpack.find(function(m) {
                return typeof m === "object" && m !== null && m.dispatch && m.subscribe;
            });
            if (fd3 && typeof fd3 === "object" && fd3.subscribe) { cachedFluxDispatcher = fd3; return fd3; }
        } catch(e) {}
        return null;
    }

    function doInit() {
        if (initialized) return;
        initialized = true;
        console.log("[Vendroid] Initializing (webpack ready)");

        var fd = findFluxDispatcher();
        if (fd) {
            try {
                fd.subscribe("MOBILE_WEB_SIDEBAR_OPEN", () => { isSidebarOpen = true; });
                fd.subscribe("MOBILE_WEB_SIDEBAR_CLOSE", () => { isSidebarOpen = false; });
                console.log("[Vendroid] FluxDispatcher subscribed OK");
            } catch(e) {
                console.error("[Vendroid] FluxDispatcher subscribe FAILED: " + e.message);
            }
        } else {
            console.error("[Vendroid] FluxDispatcher not available!");
        }

        recoverPlugins();
        tryStartPluginsStage();

        setTimeout(() => {
            try { VencordMobileNative.dismissLoadingScreen(); } catch(e) {}
        }, 800);
    }

    // Event-driven init with fast safety-net poll (50ms instead of 500ms/1000ms).
    // tryAdvanceInit() is called both by hooks (vendroidCallInitWebpack) and
    // the poll loop, so init responds as soon as conditions are met.
    var initStage = 0; // 0=need webpack, 1=need plugins/flux, 2=done
    var initAttempts = 0;
    var MAX_INIT_ATTEMPTS = 300; // 300 * 50ms = 15s

    function tryAdvanceInit() {
        if (initStage >= 2) return;

        if (initStage === 0) {
            // Stage 1: webpack init
            if (Vencord.Webpack.wreq) {
                initStage = 1;
            } else if (_vendroidCapturedWreq) {
                vendroidCallInitWebpack();
                if (Vencord.Webpack.wreq) initStage = 1;
            } else if (tryInitWebpack()) {
                initStage = 1;
            }
        }

        if (initStage === 1) {
            // Stage 2: FluxDispatcher + Plugins
            if (_vendroidCapturedWreq && typeof Vencord !== "undefined" && Vencord.Webpack && !Vencord.Webpack.wreq) {
                vendroidCallInitWebpack();
            }
            var hasPlugins = typeof Vencord !== "undefined" && Vencord.Plugins && Object.keys(Vencord.Plugins.plugins).length > 0;
            var fd = findFluxDispatcher();
            if (fd && hasPlugins) {
                console.log("[Vendroid] Init ready: FluxDispatcher OK, plugins OK");
                initStage = 2;
                doInit();
                return;
            }
        }
    }

    // Safety-net poll — much faster (50ms) than old 500ms/1000ms intervals
    function initTick() {
        if (initStage >= 2) return;
        initAttempts++;
        try {
            tryAdvanceInit();
        } catch(e) {
            console.error("[Vendroid] initTick error: " + e.message);
        }
        if (initStage >= 2) return;
        if (initAttempts >= MAX_INIT_ATTEMPTS) {
            console.error("[Vendroid] Init timed out after " + initAttempts + " attempts (" + (initAttempts * 50) + "ms)");
            initStage = 2;
            recoverPlugins();
            tryStartPluginsStage();
            return;
        }
        setTimeout(initTick, 50);
    }
    initTick();

    setTimeout(() => {
        try {
            var results = [];
            results.push("URL: " + window.location.pathname);
            var settingsPlugin = Vencord.Plugins?.plugins?.Settings;
            if (settingsPlugin) {
                results.push("Settings: enabled=" + Vencord.Plugins.isPluginEnabled("Settings") + " started=" + settingsPlugin.started + " startAt=" + settingsPlugin.startAt);
                var patchDetails = (settingsPlugin.patches || []).map(function(p, i) {
                    var hasAll = !!(p.all && p.all.length);
                    var matchStr = p.match ? p.match.toString().substring(0, 60) : (p.find || "no-match-or-find");
                    return i + ":applied=" + hasAll + ":find=" + (p.find || "none") + ":match=" + matchStr;
                });
                results.push("Patches: " + patchDetails.join("; "));
            }
            var vencordEls = document.querySelectorAll('[class*="vencord"], [class*="Vencord"], [class*="vcd"]');
            results.push("Vencord DOM els: " + vencordEls.length);
            results.push("initialized: " + initialized);
            console.log("[Vendroid] Settings diag 20s: " + results.join(" | "));
        } catch(e) {
            console.error("[Vendroid] Settings diag error: " + e.message);
        }
    }, 20000);

    let vfsState = null;
    let imgOverlay = null;

    function notifyOverlayState() {
        try { VencordMobileNative.setOverlayActive(!!(vfsState || imgOverlay)); } catch(e) {}
    }

    function delayedBlur() {
        setTimeout(() => { try { if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur(); } catch(e) {} }, 50);
        setTimeout(() => { try { if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur(); } catch(e) {} }, 200);
    }

    window.VencordMobile = {
        onBackPress() {
            // Re-sync from URL before doing anything. Discord is a SPA, so the URL
            // changes via client-side routing long before our FluxDispatcher
            // subscriptions in doInit() are live.
            try {
                var path = window.location.pathname;
                isSidebarOpen = !/\/channels\/[^\/]+\/[^\/]+/.test(path);
            } catch(e) {}

            if (vfsState) {
                exitVideoFullscreen();
                return true;
            }
            if (imgOverlay) {
                closeImageOverlay();
                return true;
            }

            var meh = getModalEscapeHandler();
            if (meh && typeof meh.action === "function") {
                try {
                    if (meh.action() === false) return true;
                } catch(e) {
                    console.error("[Vendroid] ModalEscapeHandler action threw: " + e.message);
                }
            }

            const quickCssWin = window.__VENCORD_MONACO_WIN__?.deref();
            if (quickCssWin && !quickCssWin.closed) {
                quickCssWin.close();
                delete window.__VENCORD_MONACO_WIN__;
                return true;
            }

            if (!isSidebarOpen) {
                var fd = findFluxDispatcher();
                if (fd) {
                    try {
                        fd.dispatch({ type: "MOBILE_WEB_SIDEBAR_OPEN" });
                        return true;
                    } catch(e) {
                        console.error("[Vendroid] FluxDispatcher dispatch threw: " + e.message);
                    }
                }
                // FluxDispatcher not ready yet — use history.back as a temporary fallback
                if (window.history.length > 1) {
                    window.history.back();
                    return true;
                }
                return false;
            }

            return false;
        }
    };

    const cssUrls = [
        Vencord.Api.isEquicord
            ? "https://github.com/VendroidEnhanced/plugin/releases/download/equicord/browser.css"
            : "https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.css",
        "https://raw.githubusercontent.com/VendroidEnhanced/random-files/refs/heads/main/moreFixes.css"
    ];

    function injectStyle(url, css) {
        const style = document.createElement("style");
        style.dataset.cacheUrl = url;
        style.textContent = css;
        document.documentElement.appendChild(style);
    }

    function patchMoreFixesCss(css) {
        css = css.replace(/\/\*[\s\S]*?\*\//g, "");
        css = css.replace(
            /width:\s*var\(--screen-width\)\s*!important/g,
            "width: 100vw !important"
        );
        const marker = 'div[role="dialog"]';
        let searchFrom = 0;
        while (true) {
            const idx = css.indexOf(marker, searchFrom);
            if (idx === -1) break;
            const braceStart = css.indexOf('{', idx + marker.length);
            if (braceStart === -1) { searchFrom = idx + marker.length; continue; }
            let depth = 0, i = braceStart;
            while (i < css.length) {
                if (css[i] === '{') depth++;
                else if (css[i] === '}') { depth--; if (depth === 0) break; }
                i++;
            }
            css = css.substring(0, idx) + css.substring(i + 1);
            searchFrom = idx;
        }
        return css;
    }

    const baseCss = `
html, body {
    overflow-x: hidden !important;
    max-width: 100vw !important;
}
video {
    max-width: 100% !important;
    max-height: 80vh !important;
    height: auto !important;
    object-fit: contain !important;
}
[class*="imageWrapper"]:has(video) {
    height: fit-content !important;
}
[class*="embedMedia"] img, [class*="embedImage"] img {
    max-width: 100% !important;
    height: auto !important;
}
`.trim();

    const videoPlayerCss = `
.vfs-seek {
    -webkit-appearance: none;
    appearance: none;
    background: transparent;
    cursor: pointer;
    height: 32px;
    flex: 1;
    outline: none !important;
    -webkit-tap-highlight-color: transparent;
}
.vfs-seek::-webkit-slider-runnable-track {
    height: 8px;
    background: linear-gradient(to right, #5865f2 var(--vfs-progress, 0%), rgba(255,255,255,0.3) var(--vfs-progress, 0%));
    border-radius: 4px;
}
.vfs-seek::-webkit-slider-thumb {
    -webkit-appearance: none;
    appearance: none;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: #5865f2;
    margin-top: -6px;
    border: 2px solid #fff;
}
.vfs-seek::-moz-range-track {
    height: 8px;
    background: linear-gradient(to right, #5865f2 var(--vfs-progress, 0%), rgba(255,255,255,0.3) var(--vfs-progress, 0%));
    border-radius: 4px;
}
.vfs-seek::-moz-range-thumb {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: #5865f2;
    border: 2px solid #fff;
}
.vfs-seek::-moz-range-progress {
    background: #5865f2;
    border-radius: 4px;
}
.vfs-btn {
    -webkit-tap-highlight-color: transparent;
    outline: none !important;
}
.vfs-btn:focus {
    outline: none !important;
}
`.trim();

    function formatTime(s) {
        if (isNaN(s) || !isFinite(s)) return "0:00";
        const m = Math.floor(s / 60);
        const sec = Math.floor(s % 60);
        return m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    const svgPlay = '<svg viewBox="0 0 24 24" fill="#fff" stroke="none"><polygon points="5,3 19,12 5,21"/></svg>';
    const svgPause = '<svg viewBox="0 0 24 24" fill="#fff" stroke="none"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>';
    const svgSpeaker = '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4V5z"/><path d="M19.07 4.93a10 10 0 010 14.14"/><path d="M15.54 8.46a5 5 0 010 7.07"/></svg>';
    const svgMuted = '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4V5z"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>';
    const svgClose = '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>';
    const svgFullscreen = '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 00-2 2v3m18 0V5a2 2 0 00-2-2h-3m0 18h3a2 2 0 002-2v-3M3 16v3a2 2 0 002 2h3"/></svg>';

    function exitVideoFullscreen() {
        if (!vfsState) return;
        const { video, overlay, originalParent, originalNextSibling, originalStyles, hadControls } = vfsState;
        if (originalNextSibling && originalNextSibling.parentNode === originalParent) {
            originalParent.insertBefore(video, originalNextSibling);
        } else {
            originalParent.appendChild(video);
        }
        video.setAttribute("style", originalStyles);
        video.controls = hadControls;
        overlay.remove();
        vfsState = null;
        try {
            Object.defineProperty(document, 'fullscreenElement', { get() { return null; }, configurable: true });
        } catch(e) {}
        try { document.dispatchEvent(new Event("fullscreenchange")); } catch(e) {}
        notifyOverlayState();
    }

    function enterVideoFullscreen(video) {
        if (vfsState) return;
        const originalParent = video.parentNode;
        const originalNextSibling = video.nextSibling;
        const originalStyles = video.getAttribute("style") || "";
        const hadControls = video.controls;

        video.controls = false;
        video.setAttribute("style", "max-width:100vw;max-height:calc(100vh - 110px);width:auto;height:auto;object-fit:contain;display:block;margin:0 auto;");

        const overlay = document.createElement("div");
        overlay.style.cssText = "position:fixed;top:0;left:0;width:100vw;height:100vh;background:#000;z-index:2147483647;display:flex;flex-direction:column;justify-content:center;align-items:center;outline:none;-webkit-tap-highlight-color:transparent;";

        const controlsBg = document.createElement("div");
        controlsBg.style.cssText = "position:absolute;bottom:40px;left:0;width:100%;height:80px;background:linear-gradient(transparent,rgba(0,0,0,0.9));pointer-events:none;transition:opacity 0.3s;";

        const controls = document.createElement("div");
        controls.style.cssText = "position:absolute;bottom:40px;left:0;width:100%;height:64px;display:flex;align-items:center;gap:8px;padding:0 12px;box-sizing:border-box;flex-shrink:0;transition:opacity 0.3s;outline:none;-webkit-tap-highlight-color:transparent;";
        controls.setAttribute("tabindex", "-1");

        const playBtn = document.createElement("div");
        playBtn.innerHTML = svgPause;
        playBtn.setAttribute("tabindex", "-1");
        playBtn.style.cssText = "background:none;border:none;color:#fff;padding:6px;width:40px;height:40px;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;outline:none;-webkit-tap-highlight-color:transparent;";
        playBtn.className = "vfs-btn";

        const seekBar = document.createElement("input");
        seekBar.type = "range";
        seekBar.min = 0;
        seekBar.max = 100;
        seekBar.value = 0;
        seekBar.step = 0.1;
        seekBar.className = "vfs-seek";
        seekBar.style.setProperty("--vfs-progress", "0%");
        seekBar.setAttribute("tabindex", "-1");

        const timeLabel = document.createElement("span");
        timeLabel.textContent = "0:00 / 0:00";
        timeLabel.style.cssText = "color:#fff;font-size:12px;font-family:monospace;white-space:nowrap;min-width:70px;text-align:center;flex-shrink:0;";

        const muteBtn = document.createElement("div");
        muteBtn.innerHTML = svgSpeaker;
        muteBtn.setAttribute("tabindex", "-1");
        muteBtn.style.cssText = "background:none;border:none;color:#fff;padding:6px;width:40px;height:40px;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;outline:none;-webkit-tap-highlight-color:transparent;";
        muteBtn.className = "vfs-btn";
        muteBtn.addEventListener("click", e => { e.stopPropagation(); video.muted = !video.muted; muteBtn.innerHTML = video.muted ? svgMuted : svgSpeaker; });

        const exitBtn = document.createElement("div");
        exitBtn.innerHTML = svgFullscreen;
        exitBtn.setAttribute("tabindex", "-1");
        exitBtn.style.cssText = "background:none;border:none;color:#fff;padding:6px;width:40px;height:40px;display:flex;align-items:center;justify-content:center;cursor:pointer;flex-shrink:0;outline:none;-webkit-tap-highlight-color:transparent;";
        exitBtn.className = "vfs-btn";
        exitBtn.addEventListener("click", e => { e.stopPropagation(); exitVideoFullscreen(); });

        controls.appendChild(playBtn);
        controls.appendChild(seekBar);
        controls.appendChild(timeLabel);
        controls.appendChild(muteBtn);
        controls.appendChild(exitBtn);

        playBtn.addEventListener("click", e => { e.stopPropagation(); if (video.paused) video.play(); else video.pause(); });
        seekBar.addEventListener("input", e => { e.stopPropagation(); if (video.duration) { video.currentTime = (seekBar.value / 100) * video.duration; seekBar.style.setProperty("--vfs-progress", seekBar.value + "%"); } });

        function updatePlayBtn() { playBtn.innerHTML = video.paused ? svgPlay : svgPause; }
        video.addEventListener("play", updatePlayBtn);
        video.addEventListener("pause", updatePlayBtn);
        video.addEventListener("timeupdate", () => {
            if (video.duration && !seekBar._dragging) {
                seekBar.value = (video.currentTime / video.duration) * 100;
                seekBar.style.setProperty("--vfs-progress", (seekBar.value) + "%");
                timeLabel.textContent = formatTime(video.currentTime) + " / " + formatTime(video.duration);
            }
        });
        seekBar.addEventListener("mousedown", () => { seekBar._dragging = true; });
        seekBar.addEventListener("touchstart", () => { seekBar._dragging = true; }, { passive: true });
        seekBar.addEventListener("mouseup", () => { seekBar._dragging = false; });
        seekBar.addEventListener("touchend", () => { seekBar._dragging = false; });

        let hideTimer = null;
        let controlsVisible = true;
        function showControls() {
            controlsVisible = true;
            controls.style.opacity = "1";
            controls.style.pointerEvents = "auto";
            controlsBg.style.opacity = "1";
            clearTimeout(hideTimer);
            hideTimer = setTimeout(() => {
                controlsVisible = false;
                controls.style.opacity = "0";
                controls.style.pointerEvents = "none";
                controlsBg.style.opacity = "0";
            }, 3000);
        }
        function hideControls() {
            controlsVisible = false;
            controls.style.opacity = "0";
            controls.style.pointerEvents = "none";
            controlsBg.style.opacity = "0";
            clearTimeout(hideTimer);
        }

        overlay.addEventListener("click", e => {
            if (e.target === overlay || e.target === video) {
                if (controlsVisible) { hideControls(); }
                else { showControls(); }
            }
        });
        video.addEventListener("click", e => {
            e.stopPropagation();
            if (controlsVisible) { hideControls(); }
            else { showControls(); }
        });

        overlay.appendChild(video);
        overlay.appendChild(controlsBg);
        overlay.appendChild(controls);
        document.body.appendChild(overlay);

        vfsState = { video, overlay, controlsBg, controls, originalParent, originalNextSibling, originalStyles, hadControls };
        updatePlayBtn();
        showControls();

        try {
            Object.defineProperty(document, 'fullscreenElement', { get() { return vfsState ? vfsState.video : null; }, configurable: true });
        } catch(e) {}
        try { document.dispatchEvent(new Event("fullscreenchange")); } catch(e) {}
        notifyOverlayState();
    }

    function hookVideoFullscreen() {
        const origRequestFullscreen = Element.prototype.requestFullscreen;
        const origWebkitRequestFullscreen = Element.prototype.webkitRequestFullscreen;
        const origExitFullscreen = document.exitFullscreen;
        const origWebkitExitFullscreen = document.webkitExitFullscreen;

        Element.prototype.requestFullscreen = function(options) {
            const video = this instanceof HTMLVideoElement ? this : this.querySelector?.("video");
            if (video) { enterVideoFullscreen(video); return Promise.resolve(); }
            return origRequestFullscreen.call(this, options);
        };

        if (origWebkitRequestFullscreen) {
            Element.prototype.webkitRequestFullscreen = function() {
                const video = this instanceof HTMLVideoElement ? this : this.querySelector?.("video");
                if (video) { enterVideoFullscreen(video); return Promise.resolve(); }
                return origWebkitRequestFullscreen.call(this);
            };
        }

        document.exitFullscreen = function() {
            if (vfsState) { exitVideoFullscreen(); return Promise.resolve(); }
            return origExitFullscreen.call(this);
        };

        if (origWebkitExitFullscreen) {
            document.webkitExitFullscreen = function() {
                if (vfsState) { exitVideoFullscreen(); return; }
                origWebkitExitFullscreen.call(this);
            };
        }
    }

    function closeImageOverlay() {
        if (!imgOverlay) return;
        if (imgOverlay._resetImgTransform) imgOverlay._resetImgTransform();
        try { var meh = getModalEscapeHandler(); if (meh && typeof meh.action === "function") meh.action(); } catch(e) {}
        imgOverlay.remove();
        imgOverlay = null;
        notifyOverlayState();
        delayedBlur();
    }

    function toFullResUrl(src) {
        try {
            const url = new URL(src);
            if (url.host.endsWith(".discordapp.com") || url.host.endsWith(".discordapp.net")) {
                url.searchParams.delete("width");
                url.searchParams.delete("height");
                url.searchParams.delete("size");
            }
            return url.toString();
        } catch(e) {
            return src;
        }
    }

    function isDiscordHost(urlStr) {
        try { const h = new URL(urlStr).host; return h.endsWith(".discordapp.com") || h.endsWith(".discordapp.net"); } catch(e) { return false; }
    }

    function findProxyUrl(img) {
        for (const key of Object.keys(img)) {
            if (!key.startsWith("__reactFiber$") && !key.startsWith("__reactInternalInstance$")) continue;
            let fiber = img[key];
            while (fiber) {
                const p = fiber.memoizedProps || fiber.pendingProps;
                if (p) {
                    if (p.proxyURL) return p.proxyURL;
                    if (p.proxy_url) return p.proxy_url;
                }
                fiber = fiber.return;
            }
        }
        return null;
    }

    function getBestImageUrl(img) {
        if (img.srcset) {
            for (const entry of img.srcset.split(",")) {
                const u = entry.trim().split(/\s+/)[0];
                if (isDiscordHost(u)) return toFullResUrl(u);
            }
        }
        const proxy = findProxyUrl(img);
        if (proxy) return toFullResUrl(proxy);
        if (img.dataset.safeSrc && isDiscordHost(img.dataset.safeSrc)) return toFullResUrl(img.dataset.safeSrc);
        if (img.currentSrc && isDiscordHost(img.currentSrc)) return toFullResUrl(img.currentSrc);
        if (img.src && isDiscordHost(img.src)) return toFullResUrl(img.src);
        return toFullResUrl(img.dataset.safeSrc || img.currentSrc || img.src);
    }

    function showImageInOverlay(src, isVideo) {
        closeImageOverlay();
        src = toFullResUrl(src);
        const overlay = document.createElement("div");
        overlay.style.cssText = "position:fixed;top:0;left:0;width:100vw;height:100vh;background:rgba(0,0,0,0.95);display:flex;align-items:center;justify-content:center;z-index:2147483646;outline:none;overflow:hidden;";
        overlay.setAttribute("tabindex", "-1");
        overlay.focus({ preventScroll: true });

        const img = isVideo ? document.createElement("video") : document.createElement("img");
        if (isVideo) {
            img.autoplay = true;
            img.muted = true;
            img.loop = true;
            img.playsInline = true;
        }
        img.src = src;
        img.style.cssText = "max-width:100vw;max-height:100vh;width:auto;height:auto;object-fit:contain;transform-origin:0 0;touch-action:none;";
        img.setAttribute("tabindex", "-1");
        img.draggable = false;

        let imgScale = 1, imgTx = 0, imgTy = 0;
        let pinchStartDist = 0, pinchStartScale = 1, pinchStartTx = 0, pinchStartTy = 0, pinchMidX = 0, pinchMidY = 0;
        let pinchLayoutLeft = 0, pinchLayoutTop = 0, imgLayoutWidth = 0, imgLayoutHeight = 0;
        let panStartX = 0, panStartY = 0, panStartTx = 0, panStartTy = 0;
        let activeTouches = 0;

        function clampTransform() {
            const vw = window.innerWidth, vh = window.innerHeight;
            const rw = imgLayoutWidth * imgScale, rh = imgLayoutHeight * imgScale;
            if (rw <= vw) {
                imgTx = (vw - rw) / 2 - pinchLayoutLeft;
            } else {
                const minTx = -pinchLayoutLeft - (rw - vw);
                const maxTx = -pinchLayoutLeft;
                imgTx = Math.max(minTx, Math.min(maxTx, imgTx));
            }
            if (rh <= vh) {
                imgTy = (vh - rh) / 2 - pinchLayoutTop;
            } else {
                const minTy = -pinchLayoutTop - (rh - vh);
                const maxTy = -pinchLayoutTop;
                imgTy = Math.max(minTy, Math.min(maxTy, imgTy));
            }
        }

        function updateImgTransform() {
            if (imgScale > 1) clampTransform();
            img.style.transform = "translate(" + imgTx + "px," + imgTy + "px) scale(" + imgScale + ")";
        }

        function resetImgTransform() {
            imgScale = 1; imgTx = 0; imgTy = 0;
            img.style.transform = "";
        }

        overlay._resetImgTransform = resetImgTransform;

        overlay.addEventListener("touchstart", e => {
            if (e.touches.length === 2) {
                e.preventDefault();
                const dx = e.touches[0].clientX - e.touches[1].clientX;
                const dy = e.touches[0].clientY - e.touches[1].clientY;
                pinchStartDist = Math.sqrt(dx * dx + dy * dy);
                pinchStartScale = imgScale;
                pinchStartTx = imgTx;
                pinchStartTy = imgTy;
                pinchMidX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
                pinchMidY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
                const rect = img.getBoundingClientRect();
                pinchLayoutLeft = rect.left - imgTx;
                pinchLayoutTop = rect.top - imgTy;
                imgLayoutWidth = rect.width / imgScale;
                imgLayoutHeight = rect.height / imgScale;
                activeTouches = 2;
            } else if (e.touches.length === 1 && imgScale > 1) {
                panStartX = e.touches[0].clientX;
                panStartY = e.touches[0].clientY;
                panStartTx = imgTx;
                panStartTy = imgTy;
                activeTouches = 1;
            }
        }, { passive: false });

        overlay.addEventListener("touchmove", e => {
            if (e.touches.length === 2 && activeTouches === 2) {
                e.preventDefault();
                const dx = e.touches[0].clientX - e.touches[1].clientX;
                const dy = e.touches[0].clientY - e.touches[1].clientY;
                const dist = Math.sqrt(dx * dx + dy * dy);
                const ratio = dist / pinchStartDist;
                imgScale = Math.min(Math.max(pinchStartScale * ratio, 1), 5);
                const scaleDelta = imgScale / pinchStartScale;
                const curMidX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
                const curMidY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
                imgTx = curMidX - pinchLayoutLeft - (pinchMidX - pinchLayoutLeft - pinchStartTx) * scaleDelta;
                imgTy = curMidY - pinchLayoutTop - (pinchMidY - pinchLayoutTop - pinchStartTy) * scaleDelta;
                updateImgTransform();
            } else if (e.touches.length === 1 && activeTouches === 1 && imgScale > 1) {
                e.preventDefault();
                const dx = e.touches[0].clientX - panStartX;
                const dy = e.touches[0].clientY - panStartY;
                imgTx = panStartTx + dx;
                imgTy = panStartTy + dy;
                updateImgTransform();
            }
        }, { passive: false });

        overlay.addEventListener("touchend", e => {
            if (e.touches.length === 0) {
                activeTouches = 0;
                if (imgScale <= 1.02) resetImgTransform();
            } else if (e.touches.length === 1 && activeTouches === 2) {
                panStartX = e.touches[0].clientX;
                panStartY = e.touches[0].clientY;
                panStartTx = imgTx;
                panStartTy = imgTy;
                activeTouches = 1;
            }
        });

        const closeBtn = document.createElement("div");
        closeBtn.innerHTML = svgClose;
        closeBtn.setAttribute("tabindex", "-1");
        closeBtn.style.cssText = "position:absolute;top:16px;right:16px;background:rgba(255,255,255,0.2);border:none;color:#fff;padding:8px;width:40px;height:40px;display:flex;align-items:center;justify-content:center;border-radius:4px;cursor:pointer;z-index:1;";
        closeBtn.addEventListener("click", e => { e.preventDefault(); e.stopPropagation(); closeImageOverlay(); });

        overlay.appendChild(img);
        overlay.appendChild(closeBtn);
        document.body.appendChild(overlay);
        imgOverlay = overlay;

        overlay.addEventListener("click", e => {
            if (e.target === overlay && imgScale <= 1) closeImageOverlay();
        });

        notifyOverlayState();
        delayedBlur();
    }

    function isLightboxDialog(dialog) {
        const text = (dialog.textContent || "").trim();
        if (text.length > 200) return false;
        const mediaCount = dialog.querySelectorAll("img").length + dialog.querySelectorAll("video").length;
        if (mediaCount > 2) return false;
        return true;
    }

    function dismissDiscordModal() {
        requestAnimationFrame(() => {
            const dialog = document.querySelector('div[role="dialog"]');
            if (dialog) {
                dialog.style.setProperty("display", "none", "important");
            }
            try { var meh = getModalEscapeHandler(); if (meh && typeof meh.action === "function") meh.action(); } catch(e) {}
            delayedBlur();
        });
    }

    function isInApp() {
        try {
            return window.location.pathname.startsWith("/channels/");
        } catch(e) {
            return false;
        }
    }

    function hookImageClick() {
        // Use a lightweight gate: tag-check the event target *before* any
        // DOM queries (closest, querySelector). This avoids the cost of
        // tree-walking on every single click that isn't on media.
        document.addEventListener("click", (e) => {
            if (imgOverlay) return;
            if (!isInApp()) return;

            const target = e.target;
            const tag = target.tagName;
            // Fast reject: skip immediately if the target isn't a media element
            // and doesn't contain one (rare — only wrapper divs).
            if (tag !== 'IMG' && tag !== 'VIDEO' && tag !== 'SVG' &&
                tag !== 'PICTURE' && tag !== 'IFRAME') {
                // childElementCount check is cheaper than querySelector for the common case
                if (!target.childElementCount) return;
                if (!target.querySelector('img, video')) return;
            }

            const img = e.target.closest("img");
            const video = !img ? e.target.closest("video") : null;
            if (!img && !video) return;

            const el = img || video;

            if (el.closest('svg')) return;

            if (el.closest('iframe, [data-hcaptcha-response], .hcaptcha, .captcha')) return;

            if (el.closest('[class*="avatar"], [class*="Avatar"], [class*="pfp"], [class*="Pfp"]')) return;

            if (el.closest('[class*="member"], [class*="Member"], [class*="userPopout"], [class*="UserPopout"]')) return;

            if (el.closest('[class*="status"], [class*="pill"], [class*="roleIcon"], [class*="RoleIcon"]')) return;

            if (img) {
                if (!isDiscordHost(img.src || img.currentSrc || img.dataset?.safeSrc || "")) return;

                const rect = img.getBoundingClientRect();
                if (rect.width < 200 && rect.height < 200) return;

                e.stopImmediatePropagation();
                e.preventDefault();

                showImageInOverlay(getBestImageUrl(img));

                dismissDiscordModal();
            } else if (video && video.muted && video.loop) {
                const videoSrc = video.src || video.currentSrc || "";
                if (!isDiscordHost(videoSrc)) return;

                const rect = video.getBoundingClientRect();
                if (rect.width < 200 && rect.height < 200) return;

                e.stopImmediatePropagation();
                e.preventDefault();

                showImageInOverlay(videoSrc, true);

                dismissDiscordModal();
            }
        }, true);
    }

    function initVendroidDom() {
        if (window.__vendroidDomInitDone) return;
        window.__vendroidDomInitDone = true;

        injectStyle("vendroid_image_overflow_fix", baseCss);
        injectStyle("vendroid_video_player", videoPlayerCss);
        injectStyle("vendroid_hide_clyde", "#app-mount>svg{display:none!important;}");
        hookVideoFullscreen();
        hookImageClick();

        let observerRafId = 0;
        let lastNonLightboxDialog = null;
        let lastObserverRun = 0;
        // Throttle MutationObserver to at most once per animation frame AND
        // at most once per 100ms — prevents it from firing on every single
        // DOM mutation during rapid scrolling or typing.
        const OBSERVER_MIN_INTERVAL = 100;
        const observer = new MutationObserver(() => {
            const now = Date.now();
            if (now - lastObserverRun < OBSERVER_MIN_INTERVAL) return;
            lastObserverRun = now;
            cancelAnimationFrame(observerRafId);
            observerRafId = requestAnimationFrame(() => {
            if (!isInApp()) return;
            const dialog = document.querySelector('div[role="dialog"]');
            if (!dialog || dialog.style.display === "none") { lastNonLightboxDialog = null; return; }
            if (dialog === lastNonLightboxDialog) return;
            const dialogRect = dialog.getBoundingClientRect();
            if (dialogRect.width > 0 && dialogRect.width < window.innerWidth * 0.9) { lastNonLightboxDialog = dialog; return; }
            if (!isLightboxDialog(dialog)) { lastNonLightboxDialog = dialog; return; }
            const imgs = dialog.querySelectorAll("img");
            for (const img of imgs) {
                if (img.width === 0 && img.height === 0) continue;
                if (img.closest('svg')) continue;
                const rect = img.getBoundingClientRect();
                if (rect.width < 50 && rect.height < 50) continue;
                if (!imgOverlay) {
                    showImageInOverlay(getBestImageUrl(img));
                    dialog.style.setProperty("display", "none", "important");
                    try { var meh = getModalEscapeHandler(); if (meh && typeof meh.action === "function") meh.action(); } catch(e) {}
                    delayedBlur();
                }
                break;
            }
            if (imgOverlay) return;
            const videos = dialog.querySelectorAll("video");
            for (const video of videos) {
                if (video.width === 0 && video.height === 0) continue;
                if (!video.muted || !video.loop) continue;
                const videoSrc = video.src || video.currentSrc || "";
                if (!isDiscordHost(videoSrc)) continue;
                if (!imgOverlay) {
                    showImageInOverlay(videoSrc, true);
                    dialog.style.setProperty("display", "none", "important");
                    try { var meh = getModalEscapeHandler(); if (meh && typeof meh.action === "function") meh.action(); } catch(e) {}
                    delayedBlur();
                }
                break;
            }
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });

        document.addEventListener("keydown", e => {
            if (e.key === "Escape") {
                if (vfsState) { e.preventDefault(); e.stopPropagation(); exitVideoFullscreen(); }
                else if (imgOverlay) { e.preventDefault(); e.stopPropagation(); closeImageOverlay(); }
            }
        }, true);

        cssUrls.forEach((url, idx) => {
            const cacheKey = "vendroid_css_" + url;
            const tsKey = cacheKey + "_ts";
            const now = Date.now();
            const CACHE_TTL = 6 * 60 * 60 * 1000; // 6 hours (only used to decide if cache is shown immediately)

            let cached;
            try { cached = localStorage.getItem(cacheKey); } catch(e) {}
            let cacheTs = 0;
            try { cacheTs = parseInt(localStorage.getItem(tsKey)) || 0; } catch(e) {}
            const isStale = (now - cacheTs) > CACHE_TTL;

            // Always inject cached CSS immediately for fast startup (if available)
            if (cached) {
                if (url.includes("moreFixes")) cached = patchMoreFixesCss(cached);
                injectStyle(url, cached);
            }

            // Always fetch from network (stale-while-revalidate pattern)
            // Add cache-busting query param to bypass all HTTP/CDN/DNS caches
            const bustUrl = url + (url.includes("?") ? "&" : "?") + "_t=" + now;
            fetch(bustUrl)
                .then(r => r.text())
                .then(css => {
                    if (url.includes("moreFixes")) css = patchMoreFixesCss(css);
                    try { localStorage.setItem(cacheKey, css); } catch(e) {}
                    try { localStorage.setItem(tsKey, String(now)); } catch(e) {}
                    const existing = document.querySelector(`style[data-cache-url="${url}"]`);
                    if (existing) { existing.textContent = css; }
                    else { injectStyle(url, css); }
                })
                .catch(() => {
                    if (!cached) {
                        const link = Object.assign(document.createElement("link"), {
                            rel: "stylesheet",
                            type: "text/css",
                            href: url
                        });
                        document.documentElement.appendChild(link);
                    }
                });
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initVendroidDom, { once: true });
    } else {
        initVendroidDom();
    }

})();
