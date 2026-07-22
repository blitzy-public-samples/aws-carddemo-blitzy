/*
 * bms-pfkeys.js - CardDemo BMS PF-key keyboard bridge (finding P5-04).
 *
 * Progressive enhancement only. The pfkey <button type="submit"> controls rendered on each screen
 * are the canonical controls and function fully without JavaScript. This script maps the physical
 * 3270 program-function keys (F1..F12) to the matching pfkey submit button so keyboard users get
 * terminal-faithful behaviour (e.g. pressing F3 activates the "F3 = Exit" button).
 *
 * It is served from /js (same origin) so it satisfies the application Content-Security-Policy
 * (default-src 'self', no script-src). The equivalent handler was previously an inline <script>,
 * which the CSP blocked - so the physical PF3 key did nothing on the admin menu. Moving the handler
 * to this external file restores the behaviour under CSP.
 *
 * No third-party libraries are used. ENTER is handled natively by the browser (the form submits via
 * its first submit button, which carries pfkey=ENTER), so it is intentionally not intercepted here.
 */
(function () {
    'use strict';

    /**
     * Maps a keydown event to the CardDemo pfkey token used by the submit buttons, or null when the
     * pressed key is not a program-function key. F3 -> "PF3", F12 -> "PF12", etc.
     */
    function pfKeyToken(event) {
        var key = event.key;
        if (/^F([1-9]|1[0-2])$/.test(key)) {
            return 'PF' + key.substring(1);
        }
        return null;
    }

    document.addEventListener('keydown', function (event) {
        var token = pfKeyToken(event);
        if (token === null) {
            return;
        }
        // Activate the submit button that carries this pfkey value, if the current screen exposes one.
        var button = document.querySelector(
            'button[type="submit"][name="pfkey"][value="' + token + '"]');
        if (button !== null) {
            event.preventDefault();
            button.click();
        }
    }, false);
})();
