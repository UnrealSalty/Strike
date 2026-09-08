window.Strike = window.Strike || {};

Strike.session = {
    leaving: false,
    clear: function () {
        try {
            for (var i = sessionStorage.length - 1; i >= 0; i--) {
                var key = sessionStorage.key(i);
                if (key.indexOf('strike:') === 0) sessionStorage.removeItem(key);
            }
        } catch (e) { return; }
    },
    signIn: function () {
        if (this.leaving) return;
        this.leaving = true;
        this.clear();
        document.documentElement.style.visibility = 'hidden';
        location.replace('/access');
    }
};

window.addEventListener('pageshow', function (event) {
    if (!event.persisted) return;
    document.documentElement.style.visibility = 'hidden';
    location.reload();
});
