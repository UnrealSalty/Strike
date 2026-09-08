(function () {
    'use strict';

    var modal = document.getElementById('releaseNotes');
    var open = document.getElementById('releaseNotesOpen');
    var close = document.getElementById('releaseNotesClose');

    function plain(markdown) {
        return markdown.replace(/\r\n?/g, '\n')
            .replace(/^\s*(```|~~~).*$/gm, '')
            .replace(/^ {0,3}#{1,6}\s+(.+?)\s*#*$/gm, '$1')
            .replace(/^ {0,3}>\s?/gm, '')
            .replace(/^\s*([-*_])(?:\s*\1){2,}\s*$/gm, '')
            .replace(/!?\[([^\]]+)\]\([^\n)]*\)/g, '$1')
            .replace(/\*\*([^\n]+?)\*\*|__([^\n]+?)__/g, function (_, stars, underscores) { return stars || underscores; })
            .replace(/~~([^\n]+?)~~/g, '$1')
            .replace(/`([^`\n]+)`/g, '$1')
            .replace(/(^|[\s(])\*([^*\n]+)\*(?=[\s).,!?:;]|$)/g, '$1$2')
            .replace(/(^|[\s(])_([^_\n]+)_(?=[\s).,!?:;]|$)/g, '$1$2')
            .replace(/^\s*[-*+]\s+/gm, '\u2022 ')
            .replace(/\\([\\`*_{}\[\]()#+.!>-])/g, '$1')
            .replace(/\n{3,}/g, '\n\n').trim();
    }

    Strike.releaseNotes = function (notes) {
        document.getElementById('updateNotes').textContent = plain(notes || '');
        open.disabled = !notes;
    };

    function hide() {
        modal.hidden = true;
        open.focus();
    }
    open.onclick = function () { modal.hidden = false; close.focus(); };
    close.onclick = hide;
    modal.onclick = function (event) { if (event.target === modal) hide(); };
    modal.onkeydown = function (event) {
        if (event.key === 'Escape') hide();
        if (event.key === 'Tab') { event.preventDefault(); close.focus(); }
    };
}());
