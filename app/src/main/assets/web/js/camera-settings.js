(function () {
    'use strict';

    var path = '/api/camera/settings';
    var button = document.querySelector('#cameraProfile .pick__btn');
    var saved = null;
    var picker = Strike.pick('cameraProfile', save);

    function paint(payload) {
        if (!payload.canManage) {
            document.getElementById('cameraProfile').hidden = true;
            document.getElementById('cameraProfileNote').textContent = 'Edit camera settings from the car.';
            return;
        }
        saved = payload.profile;
        picker.fill(payload.profiles);
        picker.select(saved);
        button.disabled = false;
    }

    function save(profile) {
        if (profile === saved) return;
        button.disabled = true;
        Strike.core.post(path, 'profile=' + encodeURIComponent(profile), function () {
            saved = profile;
            button.disabled = false;
            Strike.toast('Camera profile saved. Restart Recorder in Daemons to apply it.');
        }, function () {
            picker.select(saved);
            button.disabled = false;
            Strike.toast('Could not save the camera profile. Check shell access.', true);
        });
    }

    Strike.core.get(path, paint, function () {
        document.getElementById('cameraProfileValue').textContent = Strike.core.dash;
        Strike.toast('Could not load camera settings', true);
    });
}());
