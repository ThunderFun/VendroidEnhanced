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
        setupSlateBackspaceFix();
        setupTextCommandDispatcher();

        setTimeout(() => {
            try { VencordMobileNative.dismissLoadingScreen(); } catch(e) {}
        }, 800);
    }

    // Slate editor Android backspace fix.
    //
    // Slate skips deleteContentBackward events with a collapsed selection,
    // so Android WebView's native contenteditable backspace runs and desyncs
    // Slate's model. Intercept "beforeinput" at the capture phase and call
    // deleteBackward('character') directly, preventing the default.
    var _vendroidSlateFixInstalled = false;
    var _vendroidSlateRetryCount = 0;

    function setupSlateBackspaceFix() {
        if (_vendroidSlateFixInstalled) return;
        // Document-level capture listener targeting [data-slate-editor]; avoids
        // needing the element to exist at setup time and handles recreation.
        try {
            document.addEventListener("beforeinput", function(e) {
                // Only handle deleteContentBackward with collapsed selection
                if (e.inputType !== "deleteContentBackward") return;
                var target = e.target;
                if (!target || !target.hasAttribute) return;
                // Only target the Slate editor contenteditable
                if (!target.hasAttribute("data-slate-editor") &&
                    !target.closest("[data-slate-editor]")) return;
                var sel = document.getSelection();
                if (!sel || !sel.isCollapsed) return; // let Slate handle non-collapsed

                // Find the Slate editor instance via the React fiber
                var editor = findSlateEditorFromDOM(target);
                if (!editor || typeof editor.deleteBackward !== "function") return;

                // Call Slate's deleteBackward to keep state in sync
                e.preventDefault();
                try {
                    editor.deleteBackward("character");
                } catch(err) {
                    console.error("[Vendroid] Slate backspace fix error: " + err.message);
                }
            }, true); // capture phase — runs before Slate's own handler
            _vendroidSlateFixInstalled = true;
            console.log("[Vendroid] Slate backspace fix installed");
        } catch(e) {
            console.error("[Vendroid] setupSlateBackspaceFix failed: " + e.message);
        }
    }

    // Walk the React fiber tree from a DOM element to find the Slate editor
    // instance (Discord's class eE receives `editor` as a prop). Walk up to
    // 30 fibers looking for memoizedProps.editor with deleteBackward.
    function findSlateEditorFromDOM(element) {
        try {
            // Find the React fiber key on this element
            var fiberKey = null;
            for (var key in element) {
                if (key.startsWith("__reactFiber") || key.startsWith("__reactInternalInstance")) {
                    fiberKey = key;
                    break;
                }
            }
            if (!fiberKey) return null;

            var fiber = element[fiberKey];
            var visited = 0;
            while (fiber && visited < 30) {
                var props = fiber.memoizedProps;
                if (props && props.editor && typeof props.editor.deleteBackward === "function" &&
                    typeof props.editor.insertText === "function") {
                    return props.editor;
                }
                fiber = fiber.return;
                visited++;
            }
        } catch(e) {
            // React fiber access can fail if the element was unmounted
        }
        return null;
    }

    // Built-in text command dispatcher (/me, /tableflip, /shrug, …).
    //
    // The command browser's autocomplete UI has touch/IME issues on Android,
    // so built-in text commands may not dispatch. Patch MessageActions.sendMessage
    // to intercept "/" messages and apply the transform directly.
    //
    // Patched directly because MessageEvents.addMessagePreSendListener's webpack
    // find:".handleSendMessage,onResize:" no longer exists in the mobile bundle,
    // so the pre-send hook silently never fires.

    // Built-in TEXT commands whose execute() is a pure string transform (from
    // Discord module 917012, array `x`). Commands needing stores/RPCs (/nick,
    // /kick, /ban, /timeout, /thread, /msg, /roll-dice) are omitted and fall
    // through to the command browser / plain text.
    var VENDROID_TEXT_COMMANDS = {
        shrug:    function(msg) { return { content: (msg + " \xaf\\_(\u30C4)_/\xaf").trim() }; },
        tableflip:function(msg) { return { content: (msg + " (\u256F\u00B0\u25A1\u00B0\u256F\uFE35) \u253B\u2501\u253B").trim() }; },
        unflip:   function(msg) { return { content: (msg + " \u252C\u2500\u252C\u30CE( \xBA _ \xBA\u30CE)").trim() }; },
        tts:      function(msg) { return { content: msg, tts: true }; },
        me:       function(msg) { return { content: "_" + msg + "_" }; },
        spoiler:  function(msg) { return { content: ("||" + msg + "||").trim() }; }
    };

    var _vendroidCmdPatched = false;
    var _vendroidCmdRetryCount = 0;

    function setupTextCommandDispatcher() {
        if (_vendroidCmdPatched) return;
        try {
            var Common = Vencord.Webpack && Vencord.Webpack.Common;
            var MessageActions = Common && Common.MessageActions;
            if (!MessageActions || typeof MessageActions.sendMessage !== "function") {
                // MessageActions not ready yet — retry for up to ~15s
                if (_vendroidCmdRetryCount++ < 300) {
                    setTimeout(setupTextCommandDispatcher, 50);
                }
                return;
            }

            var origSendMessage = MessageActions.sendMessage;
            if (origSendMessage.__vendroidCmdPatched) {
                _vendroidCmdPatched = true;
                return;
            }

            MessageActions.sendMessage = function(channelId, messageObj, flag, options) {
                try {
                    var content = messageObj && typeof messageObj.content === "string" ? messageObj.content : "";
                    // Only intercept messages that start with "/"
                    if (content && content.charCodeAt(0) === 0x2F) { // 0x2F = '/'
                        // Parse: /commandName rest-of-line
                        var match = content.match(/^\/(\S+)\s*([\s\S]*)$/);
                        if (match) {
                            var cmdName = match[1].toLowerCase();
                            var msgArg = match[2] || "";
                            var executor = VENDROID_TEXT_COMMANDS[cmdName];
                            if (executor) {
                                // Run the command's execute transform
                                var result = executor(msgArg);
                                if (result && typeof result.content === "string") {
                                    // Replace content in the message object and
                                    // let the original sendMessage handle it.
                                    messageObj.content = result.content;
                                    if (result.tts) messageObj.tts = true;
                                }
                            }
                        }
                    }
                } catch(e) {
                    console.error("[Vendroid] text command dispatcher error: " + e.message);
                    // On error, let the original send proceed unchanged
                }
                // Call original sendMessage with (possibly modified) args
                return origSendMessage.apply(this, arguments);
            };
            // Mark patched so we don't double-patch on re-injection
            MessageActions.sendMessage.__vendroidCmdPatched = true;
            _vendroidCmdPatched = true;
            console.log("[Vendroid] Text command dispatcher installed (/me, /shrug, /tableflip, /unflip, /tts, /spoiler)");
        } catch(e) {
            console.error("[Vendroid] setupTextCommandDispatcher failed: " + e.message);
            if (_vendroidCmdRetryCount++ < 300) {
                setTimeout(setupTextCommandDispatcher, 50);
            }
        }
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
    let imgOverlayOpenTime = 0;

    function notifyOverlayState() {
        try { VencordMobileNative.setOverlayActive(!!(vfsState || imgOverlay)); } catch(e) {}
    }

    function delayedBlur() {
        setTimeout(() => { try { if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur(); } catch(e) {} }, 50);
        setTimeout(() => { try { if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur(); } catch(e) {} }, 200);
    }

    window.VencordMobile = {
        onBackPress() {
            if (!initialized) {
                try {
                    var path = window.location.pathname;
                    isSidebarOpen = !/\/channels\/[^\/]+\/[^\/]+/.test(path);
                } catch(e) {}
            }

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
            ? "https://vde-builds.nin0.dev/equicord/browser.css"
            : "https://vde-builds.nin0.dev/vencord/browser.css",
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
        const segments = [];
        let pos = 0;
        while (true) {
            const idx = css.indexOf(marker, pos);
            if (idx === -1) { segments.push(css.substring(pos)); break; }
            segments.push(css.substring(pos, idx));
            const braceStart = css.indexOf('{', idx + marker.length);
            if (braceStart === -1) { pos = idx + marker.length; continue; }
            let depth = 0, i = braceStart;
            while (i < css.length) {
                if (css[i] === '{') depth++;
                else if (css[i] === '}') { depth--; if (depth === 0) break; }
                i++;
            }
            pos = i + 1;
        }
        return segments.join('');
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
[class*="imageWrapper"]:has(>video) {
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
        video.pause();
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
        overlay.style.cssText = "position:fixed;top:0;left:0;width:100vw;height:100vh;background:#000;z-index:2147483647;display:flex;flex-direction:column;justify-content:center;align-items:center;outline:none;-webkit-tap-highlight-color:transparent;touch-action:none;";

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

        // Start playback if video is not already playing (e.g. opened from poster state)
        if (video.paused) {
            video.play().catch(() => {
                // Unmuted autoplay may be blocked; if so retry muted
                if (!video.muted) {
                    video.muted = true;
                    video.play().catch(() => {});
                }
            });
        }

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
            const host = url.host;
            if (host.endsWith(".discordapp.com") || host.endsWith(".discordapp.net") ||
                host.endsWith(".discord.com") || host.endsWith(".discord.net")) {
                url.searchParams.delete("width");
                url.searchParams.delete("height");
                url.searchParams.delete("size");
            }
            return url.toString();
        } catch(e) {
            return src;
        }
    }

    function findReactUrl(el, propNames) {
        for (const key of Object.keys(el)) {
            if (!key.startsWith("__reactFiber$") && !key.startsWith("__reactInternalInstance$")) continue;
            let fiber = el[key];
            let depth = 0;
            while (fiber && depth < 5) {
                const p = fiber.memoizedProps || fiber.pendingProps;
                if (p) {
                    for (const name of propNames) {
                        const val = p[name];
                        if (typeof val === "string" && (val.startsWith("http") || val.startsWith("blob:"))) return val;
                    }
                }
                fiber = fiber.return;
                depth++;
            }
        }
        return null;
    }

    function findProxyUrl(img) {
        return findReactUrl(img, ["proxyURL", "proxy_url"]);
    }

    function findVideoUrl(video) {
        return findReactUrl(video, ["src", "url", "proxyURL", "proxy_url", "original", "uri", "source"]);
    }

    function looksLikeVideoUrl(urlStr) {
        if (typeof urlStr !== "string" || urlStr.length === 0) return false;
        const lower = urlStr.toLowerCase();
        if (lower.startsWith("blob:")) return true;
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") ||
               lower.includes(".mp4?") || lower.includes(".webm?") || lower.includes(".mov?");
    }

    function getBestVideoUrl(video) {
        // 1. Trust what the browser resolved and is actually playing.
        //    currentSrc is guaranteed to be a valid media source or empty.
        if (video.currentSrc && video.currentSrc.length > 0) {
            return toFullResUrl(video.currentSrc);
        }
        // 2. Check explicit <source> children (skip ones typed as images).
        const sources = video.querySelectorAll("source");
        for (const s of sources) {
            if (s.src && s.src.length > 0) {
                const type = s.getAttribute("type") || "";
                if (!type.startsWith("image/")) {
                    return toFullResUrl(s.src);
                }
            }
        }
        // 3. Fallbacks: src attr, data attr, React props.
        if (video.src && video.src.length > 0) {
            return toFullResUrl(video.src);
        }
        if (video.dataset && video.dataset.safeSrc && video.dataset.safeSrc.length > 0) {
            return toFullResUrl(video.dataset.safeSrc);
        }
        const reactUrl = findVideoUrl(video);
        if (reactUrl) return toFullResUrl(reactUrl);
        return null;
    }

    function getBestImageUrl(img) {
        // Try proxy first (Discord React prop with full-res URL)
        const proxy = findProxyUrl(img);
        if (proxy) return toFullResUrl(proxy);

        // Try srcset for high-res variants
        if (img.srcset) {
            for (const entry of img.srcset.split(",")) {
                const u = entry.trim().split(/\s+/)[0];
                if (u && u.length > 0) return toFullResUrl(u);
            }
        }

        // Fallback to whatever the browser has resolved
        if (img.currentSrc && img.currentSrc.length > 0) return toFullResUrl(img.currentSrc);
        if (img.src && img.src.length > 0) return toFullResUrl(img.src);
        if (img.dataset && img.dataset.safeSrc && img.dataset.safeSrc.length > 0) return toFullResUrl(img.dataset.safeSrc);

        return null;
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

        function onMediaReady() {
            const vw = window.innerWidth, vh = window.innerHeight;
            const nw = isVideo ? img.videoWidth : img.naturalWidth;
            const nh = isVideo ? img.videoHeight : img.naturalHeight;
            if (nw > 0 && nh > 0 && (nw < vw || nh < vh)) {
                const scale = Math.min(vw / nw, vh / nh);
                img.style.width = Math.round(nw * scale) + "px";
                img.style.height = Math.round(nh * scale) + "px";
            }
        }
        if (isVideo) {
            img.onloadedmetadata = onMediaReady;
            img.onerror = function() { closeImageOverlay(); };
        } else {
            img.onload = onMediaReady;
            img.onerror = function() { closeImageOverlay(); };
        }

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
        imgOverlayOpenTime = Date.now();

        if (isVideo) {
            if (img.readyState >= 1 && img.videoWidth > 0) onMediaReady();
        } else {
            if (img.complete && img.naturalWidth > 0) onMediaReady();
        }

        overlay.addEventListener("click", e => {
            if (e.target === overlay && imgScale <= 1 && Date.now() - imgOverlayOpenTime > 300) closeImageOverlay();
        });

        notifyOverlayState();
        delayedBlur();
    }

    // Decorative / non-attachment media that should never trigger the overlay.
    // One CSS selector string so isAttachmentImage(), isLightboxDialog, and
    // findImageFromTarget can do a single closest() walk.
    const DECORATIVE_UI_SELECTORS = [
        'svg',
        'iframe, [data-hcaptcha-response], .hcaptcha, .captcha',
        // Avatars / profile pictures
        '[class*="avatar"], [class*="Avatar"], [class*="pfp"], [class*="Pfp"]',
        // Member list / user popouts
        '[class*="member"], [class*="Member"], [class*="userPopout"], [class*="UserPopout"]',
        // Status / role / presence icons
        '[class*="status"], [class*="pill"], [class*="roleIcon"], [class*="RoleIcon"]',
        // Emoji (inline + reactions)
        '[class*="emoji"], [class*="Emoji"], [class*="reaction"], [class*="Reaction"]',
        // Stickers
        '[class*="sticker"], [class*="Sticker"]',
        // Guild / server / channel icons & banners
        '[class*="guildIcon"], [class*="GuildIcon"], [class*="serverIcon"], [class*="ServerIcon"], [class*="channelIcon"], [class*="ChannelIcon"], [class*="banner"], [class*="Banner"]',
        // File-type / attachment placeholder icons (not real media)
        '[class*="fileIcon"], [class*="FileIcon"], [class*="attachmentIcon"], [class*="AttachmentIcon"], [class*="mediaAttachmentIcon"]',
        // Decorative badges / nitro / boost icons
        '[class*="BadgeIcon"], [class*="boost"], [class*="Boost"], [class*="nitro"], [class*="Nitro"]',
        // Collectibles shop / profile-frame preview cards (shop UI, not chat
        // media). NOTE: do NOT add [aria-hidden="true"] — Discord marks the
        // inner media of real GIF/image wrappers as aria-hidden too, so that
        // selector would block legitimate GIFs.
        '[class*="productCard"], [class*="productPreview"], [class*="profileFrame"], [class*="profileContainer"], [class*="previewContainer"], [class*="sampleProfile"]'
    ].join(',');

    function isLightboxDialog(dialog) {
        const text = (dialog.textContent || "").trim();
        if (text.length > 500) return false;

        const buttons = dialog.querySelectorAll('button, [role="button"]');
        let actionButtonCount = 0;
        for (const btn of buttons) {
            if ((btn.textContent || "").trim().length > 0) actionButtonCount++;
        }
        if (actionButtonCount >= 2) return false;

        const mediaCount = dialog.querySelectorAll("img, video").length;
        if (mediaCount < 1) return false;
        return true;
    }

    // True if [el] is a real chat media element the overlay should take over,
    // not a decorative icon in a dialog. Requires: passes isAttachmentImage(),
    // >= 120px on the smaller side, and occupies >= 30% of the dialog area.
    function isDialogMediaOpenable(el, dialog) {
        if (!el) return false;
        if (!isAttachmentImage(el)) return false;
        const rect = el.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) return false;
        if (Math.min(rect.width, rect.height) < 120) return false;
        const dRect = dialog.getBoundingClientRect();
        const dialogArea = Math.max(1, dRect.width * dRect.height);
        if ((rect.width * rect.height) / dialogArea < 0.30) return false;
        return true;
    }

    function dismissDiscordModal() {
        function tryDismiss() {
            const dialog = document.querySelector('div[role="dialog"]');
            if (dialog && isLightboxDialog(dialog)) {
                dialog.style.setProperty("display", "none", "important");
            }
            try { var meh = getModalEscapeHandler(); if (meh && typeof meh.action === "function") meh.action(); } catch(e) {}
            delayedBlur();
        }
        tryDismiss();
        requestAnimationFrame(tryDismiss);
        setTimeout(tryDismiss, 50);
        setTimeout(tryDismiss, 150);
        setTimeout(tryDismiss, 300);
    }

    function isGifVideo(el) {
        if (el.tagName !== 'VIDEO') return false;

        // Class I: explicitly labeled GIF
        if (el.getAttribute("aria-label") === "GIF") return true;

        // Class II: looping mute micro-clips (<15 s)
        if (el.loop && el.muted) {
            if (el.readyState >= 1) {
                return el.duration > 0 && el.duration <= 15;
            }
            // If metadata not loaded yet, give benefit of doubt for GIF UI
            return true;
        }

        // Class III: long videos (> 60 s) are never GIFs
        if (el.readyState >= 1 && el.duration > 60) return false;

        // Default: if it has native controls or is explicitly not looping, it's a video
        if (el.controls) return false;
        if (!el.loop) return false;

        return true;
    }

    function isInApp() {
        try {
            return window.location.pathname.startsWith("/channels/");
        } catch(e) {
            return false;
        }
    }

    let pendingImageClickTarget = null;
    let pendingImageStartX = 0, pendingImageStartY = 0;
    let pendingImageMaxDistSq = 0;
    let lastImagePointerDownTime = 0;

    function isAttachmentImage(el) {
        if (!el) return false;
        // One closest() walk against the shared decorative-UI blocklist (see
        // DECORATIVE_UI_SELECTORS above).
        if (el.closest(DECORATIVE_UI_SELECTORS)) return false;
        return true;
    }

    function findImageFromTarget(target) {
        // Direct hit on media element
        if (target.tagName === 'VIDEO') return target;
        if (target.tagName === 'IMG') return target;

        // Walk up looking for media. querySelector is necessary because Discord
        // wraps media in deep nested containers; direct-child iteration misses
        // most messages. Spatial proximity (PROX=150px) prevents picking media
        // from a different message at high ancestors. The walk bails if an
        // ancestor is a decorative-UI container, and querySelector candidates
        // must pass isAttachmentImage() before being accepted.
        const PROX = 150;
        let node = target;
        for (let i = 0; i < 6 && node; i++) {
            // If we climbed into a decorative container, the tap was on UI
            // chrome, not chat media — stop searching.
            if (node.matches && node.matches(DECORATIVE_UI_SELECTORS)) return null;
            if (node.tagName === 'VIDEO') return node;
            if (node.tagName === 'IMG') return node;
            if (node.querySelector) {
                const video = node.querySelector('video');
                if (video && isAttachmentImage(video)) {
                    const vRect = video.getBoundingClientRect();
                    if (vRect.width > 0 && vRect.height > 0) {
                        const tRect = target.getBoundingClientRect();
                        if (Math.abs(vRect.top - tRect.top) < PROX && Math.abs(vRect.left - tRect.left) < PROX) return video;
                    }
                }
                const img = node.querySelector('img');
                if (img && isAttachmentImage(img)) {
                    const iRect = img.getBoundingClientRect();
                    if (iRect.width > 0 && iRect.height > 0) {
                        const tRect = target.getBoundingClientRect();
                        if (Math.abs(iRect.top - tRect.top) < PROX && Math.abs(iRect.left - tRect.left) < PROX) return img;
                    }
                }
            }
            node = node.parentElement;
        }
        return null;
    }

    function hookImageClick() {

        function onPointerDown(x, y, target) {
            if (imgOverlay) return;
            if (vfsState) return;
            if (!isInApp()) return;
            pendingImageClickTarget = null;
            pendingImageStartX = x;
            pendingImageStartY = y;
            pendingImageMaxDistSq = 0;

            const el = findImageFromTarget(target);
            if (!el) return;
            if (!isAttachmentImage(el)) return;

            if (el.tagName === 'IMG') {
                const rect = el.getBoundingClientRect();
                if (rect.width < 50 && rect.height < 50 && !el.srcset) return;
                pendingImageClickTarget = el;
                lastImagePointerDownTime = Date.now();
            } else if (el.tagName === 'VIDEO') {
                const rect = el.getBoundingClientRect();
                if (rect.width < 50 && rect.height < 50) return;

                if (!isGifVideo(el)) {
                    // Real (non-GIF) videos: only intercept if already playing,
                    // or if user tapped directly on the <video> element itself.
                    // If user tapped a play-button overlay on a paused video,
                    // let Discord handle the first click to start playback.
                    const directHit = (target === el);
                    if (el.paused && !directHit) return;
                }

                pendingImageClickTarget = el;
                lastImagePointerDownTime = Date.now();
            }
        }

        function onPointerMove(x, y) {
            if (!pendingImageClickTarget) return;
            const dx = x - pendingImageStartX;
            const dy = y - pendingImageStartY;
            const distSq = dx * dx + dy * dy;
            if (distSq > pendingImageMaxDistSq) pendingImageMaxDistSq = distSq;
        }

        function onPointerUp(e, x, y) {
            if (!pendingImageClickTarget) return;
            const el = pendingImageClickTarget;
            pendingImageClickTarget = null;
            if (imgOverlay) return;
            if (vfsState) return;
            if (pendingImageMaxDistSq > 2500) return;

            if (el.tagName !== 'VIDEO') return;
            const videoSrc = getBestVideoUrl(el);
            if (!videoSrc) return;

            e.stopImmediatePropagation();
            e.stopPropagation();
            e.preventDefault();

            if (isGifVideo(el)) {
                showImageInOverlay(videoSrc, true);
            } else {
                enterVideoFullscreen(el);
            }
            dismissDiscordModal();
        }

        window.addEventListener("pointerdown", (e) => {
            onPointerDown(e.clientX, e.clientY, e.target);
        }, true);

        window.addEventListener("pointermove", (e) => {
            onPointerMove(e.clientX, e.clientY);
        }, true);

        window.addEventListener("pointercancel", () => {
            pendingImageClickTarget = null;
        }, true);

        window.addEventListener("pointerup", (e) => {
            onPointerUp(e, e.clientX, e.clientY);
        }, true);

        // Backup touch handlers for browsers where pointer events are unreliable.
        window.addEventListener("touchstart", (e) => {
            if (e.touches.length !== 1) return;
            onPointerDown(e.touches[0].clientX, e.touches[0].clientY, e.target);
        }, { passive: true, capture: true });

        window.addEventListener("touchmove", (e) => {
            if (e.touches.length !== 1) return;
            onPointerMove(e.touches[0].clientX, e.touches[0].clientY);
        }, { passive: true, capture: true });

        window.addEventListener("touchend", (e) => {
            if (e.changedTouches.length !== 1) return;
            onPointerUp(e, e.changedTouches[0].clientX, e.changedTouches[0].clientY);
        }, { passive: false, capture: true });

        window.addEventListener("click", (e) => {
            if (!isInApp()) return;
            if (vfsState) return;

            if (imgOverlay && imgOverlay.contains(e.target)) return;

            let el = null;
            if (pendingImageClickTarget && pendingImageMaxDistSq <= 2500) {
                el = pendingImageClickTarget;
            }
            pendingImageClickTarget = null;
            if (!el) {
                el = findImageFromTarget(e.target);
            }
            if (!el) return;
            if (!isAttachmentImage(el)) return;

            if (imgOverlay) {
                e.stopImmediatePropagation();
                e.preventDefault();
                return;
            }

            if (el.tagName === 'IMG') {
                const rect = el.getBoundingClientRect();
                if (rect.width < 50 && rect.height < 50 && !el.srcset) return;

                e.stopImmediatePropagation();
                e.preventDefault();

                showImageInOverlay(getBestImageUrl(el));
                dismissDiscordModal();
            } else if (el.tagName === 'VIDEO') {
                const rect = el.getBoundingClientRect();
                if (rect.width < 50 && rect.height < 50) return;

                if (!isGifVideo(el)) {
                    // Real (non-GIF) videos: only intercept if already playing,
                    // or if user clicked directly on the <video> element itself.
                    // If user clicked a play-button overlay on a paused video,
                    // let Discord handle the first click to start playback.
                    const directHit = (e.target === el);
                    if (el.paused && !directHit) return;
                }

                const videoSrc = getBestVideoUrl(el);
                if (!videoSrc) return;

                e.stopImmediatePropagation();
                e.preventDefault();

                if (isGifVideo(el)) {
                    showImageInOverlay(videoSrc, true);
                } else {
                    enterVideoFullscreen(el);
                }
                dismissDiscordModal();
            }
        }, true);

    }

    function initVendroidDom() {
        if (window.__vendroidDomInitDone) return;
        window.__vendroidDomInitDone = true;

        injectStyle("vendroid_static", baseCss + "\n" + videoPlayerCss);
        hookVideoFullscreen();
        hookImageClick();

        function forcePlayGifVideos() {
            document.querySelectorAll("video").forEach(v => {
                if (!isGifVideo(v)) return;
                v.setAttribute("playsinline", "");
                v.setAttribute("autoplay", "");
                if (v.paused && v.readyState >= 1) {
                    v.play().catch(() => {});
                }
            });
        }
        forcePlayGifVideos();
        document.addEventListener("loadeddata", e => {
            if (e.target.tagName === "VIDEO" && isGifVideo(e.target) && e.target.paused) {
                e.target.play().catch(() => {});
            }
        }, true);

        let observerRafId = 0;
        let lastNonLightboxDialog = null;
        let lastNonLightboxDialogHash = "";
        let lastObserverRun = 0;
        // Throttle MutationObserver to at most once per animation frame AND
        // at most once per 30ms — prevents it from firing on every single
        // DOM mutation during rapid scrolling or typing.
        const OBSERVER_MIN_INTERVAL = 30;
        const observer = new MutationObserver(() => {
            const now = Date.now();
            if (now - lastObserverRun < OBSERVER_MIN_INTERVAL) return;
            lastObserverRun = now;
            cancelAnimationFrame(observerRafId);
            observerRafId = requestAnimationFrame(() => {
            if (!isInApp()) return;
            forcePlayGifVideos();
            const dialog = document.querySelector('div[role="dialog"]');
            if (!dialog || dialog.style.display === "none") { lastNonLightboxDialog = null; lastNonLightboxDialogHash = ""; return; }
            const dialogHash = dialog.innerHTML.length + "-" + dialog.querySelectorAll("img, video").length;
            if (dialog === lastNonLightboxDialog && dialogHash === lastNonLightboxDialogHash) return;
            const dialogRect = dialog.getBoundingClientRect();
            if (dialogRect.width > 0 && dialogRect.width < window.innerWidth * 0.5) { lastNonLightboxDialog = dialog; lastNonLightboxDialogHash = dialogHash; return; }
            if (!isLightboxDialog(dialog)) { lastNonLightboxDialog = dialog; lastNonLightboxDialogHash = dialogHash; return; }
            const imgs = dialog.querySelectorAll("img");
            for (const img of imgs) {
                // Skip zero-size and decorative media; isDialogMediaOpenable
                // enforces minimum size and dialog-area fraction.
                if (!isDialogMediaOpenable(img, dialog)) continue;
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
                if (!isDialogMediaOpenable(video, dialog)) continue;
                if (!isGifVideo(video)) continue;
                const videoSrc = getBestVideoUrl(video);
                if (!videoSrc) continue;
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

        var fetchedCssCount = 0;
        var fetchedCssBuffer = [];
        function flushFetchedCss() {
            if (fetchedCssCount < cssUrls.length) return;
            injectStyle("vendroid_fetched", fetchedCssBuffer.join("\n"));
        }
        function cssCacheKey(url) {
            var h = 0;
            for (var i = 0; i < url.length; i++) {
                h = ((31 * h) + url.charCodeAt(i)) | 0;
            }
            return "css_cache_vde_" + h;
        }
        cssUrls.forEach((url, idx) => {
            var cached = null;
            try {
                if (window.VencordMobileNative && window.VencordMobileNative.getCssCache) {
                    cached = window.VencordMobileNative.getCssCache(cssCacheKey(url));
                }
            } catch(e) { cached = null; }
            if (cached) {
                if (url.includes("moreFixes")) cached = patchMoreFixesCss(cached);
                fetchedCssBuffer[idx] = cached;
                fetchedCssCount++;
                flushFetchedCss();
                return;
            }
            fetch(url)
                .then(r => r.text())
                .then(css => {
                    if (url.includes("moreFixes")) css = patchMoreFixesCss(css);
                    fetchedCssBuffer[idx] = css;
                    fetchedCssCount++;
                    flushFetchedCss();
                    try {
                        if (window.VencordMobileNative && window.VencordMobileNative.setString) {
                            window.VencordMobileNative.setString(cssCacheKey(url), css);
                        }
                    } catch(e) {}
                })
                .catch(() => {
                    var link = Object.assign(document.createElement("link"), {
                        rel: "stylesheet",
                        type: "text/css",
                        href: url
                    });
                    document.documentElement.appendChild(link);
                });
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initVendroidDom, { once: true });
    } else {
        initVendroidDom();
    }

})();
