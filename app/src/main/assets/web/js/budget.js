(function () {
    'use strict';

    window.Strike = window.Strike || {};

    function megabytes(mb) {
        if (mb < 1024) {
            return mb + ' MB';
        }
        return (mb / 1024).toFixed(1) + ' GB';
    }

    // An unmounted or full volume has no numbers to show and cannot be chosen.
    function volumeOf(payload, location) {
        for (var i = 0; i < payload.volumes.length; i++) {
            var volume = payload.volumes[i];
            if (volume.location === location) {
                return volume.mounted ? volume : null;
            }
        }
        return null;
    }

    /**
     * The storage block of both settings modals: where to write and how much of
     * the volume to claim. Recordings and Surveillance keep separate keys and
     * separate budgets over the same markup.
     */
    Strike.budget = function (plan) {
        var slider = document.querySelector('.slider[data-key="' + plan.budget + '"]');

        function paintLocation(payload) {
            var location = document.getElementById('location');
            Strike.core.press(location, payload.values[plan.location]);
            var rows = location.getElementsByTagName('button');
            for (var i = 0; i < rows.length; i++) {
                var volume = volumeOf(payload, rows[i].getAttribute('data-value'));
                var size = rows[i].getElementsByTagName('span')[1];
                rows[i].disabled = !volume || !volume.usable;
                size.textContent = !volume
                    ? Strike.core.dash
                    : volume.usable
                        ? megabytes(volume.freeMb) + ' free of ' + megabytes(volume.totalMb)
                        : 'Full';
            }
        }

        function paintUsage(usedMb, budgetMb) {
            var known = usedMb !== null;
            document.getElementById('usageUsed').textContent = known ? megabytes(usedMb) : Strike.core.dash;
            document.getElementById('usageLimit').textContent = known ? megabytes(budgetMb) : Strike.core.dash;
            var share = known && budgetMb > 0 ? Math.round(usedMb * 100 / budgetMb) : null;
            Strike.core.meter('usageFill', share, share !== null && share >= 100 ? 'warn' : null);
        }

        function paint(payload) {
            paintLocation(payload);
            var volume = volumeOf(payload, payload.values[plan.location]);
            var usable = volume && volume.usable;
            var budgetMb = payload.values[plan.budget];
            if (usable) {
                slider.max = volume.ceilingMb;
            }
            slider.disabled = !usable;
            slider.value = budgetMb;
            document.getElementById('budget').textContent = usable ? megabytes(budgetMb) : Strike.core.dash;
            document.getElementById('budgetFloor').textContent = megabytes(Number(slider.min));
            document.getElementById('budgetCeiling').textContent =
                usable ? megabytes(volume.ceilingMb) : Strike.core.dash;
            paintUsage(usable ? volume.usedMb : null, budgetMb);
            paintShared(volume);
        }

        // Both features can point at one card, and then the limit each can
        // claim is what the other has not already reserved.
        function paintShared(volume) {
            var note = document.getElementById('shared');
            if (!volume || !volume.reservedMb) {
                note.hidden = true;
                return;
            }
            note.hidden = false;
            note.textContent = plan.other + ' has reserved ' +
                megabytes(volume.reservedMb) + ' of this volume.';
        }

        document.getElementById('location').onclick = function (event) {
            var row = Strike.core.buttonIn(event, this);
            if (row) {
                plan.save(plan.location, row.getAttribute('data-value'));
            }
        };

        slider.oninput = function () {
            document.getElementById('budget').textContent = megabytes(Number(this.value));
        };
        slider.onchange = function () {
            plan.save(plan.budget, this.value);
        };

        return { paint: paint };
    };
}());
