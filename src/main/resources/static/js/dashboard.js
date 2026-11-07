(function () {
    "use strict";

    var table = document.getElementById("serversTable");
    if (!table) {
        return;
    }

    var tbody = document.getElementById("serversBody");
    var searchInput = document.getElementById("serverSearch");
    var emptyRow = document.getElementById("emptyFilterRow");
    var visibleCount = document.getElementById("visibleCount");
    var sortButtons = Array.prototype.slice.call(document.querySelectorAll(".sort-btn"));
    var collator = new Intl.Collator("ru", {numeric: true, sensitivity: "base"});

    var sortKey = null;
    var sortDirection = 1;

    function dataRows() {
        return Array.prototype.slice.call(tbody.querySelectorAll("tr[data-name]"));
    }

    function rawValue(row, key) {
        if (key === "name" || key === "ip") {
            return (row.dataset[key] || "").toLowerCase();
        }
        if (key === "up") {
            return row.dataset.up === "true" ? 1 : 0;
        }
        var raw = row.dataset[key];
        if (raw === undefined || raw === null || raw === "") {
            return null;
        }
        var number = Number(raw);
        return Number.isFinite(number) ? number : null;
    }

    function applyFilter() {
        var query = (searchInput.value || "").trim().toLowerCase();
        var visible = 0;
        dataRows().forEach(function (row) {
            var matches = !query || (row.dataset.name || "").toLowerCase().indexOf(query) !== -1;
            row.hidden = !matches;
            if (matches) {
                visible++;
            }
        });
        if (emptyRow) {
            emptyRow.hidden = visible !== 0;
        }
        if (visibleCount) {
            var total = dataRows().length;
            visibleCount.textContent = query
                ? "Показано " + visible + " из " + total
                : "Всего серверов: " + total;
        }
    }

    function compareRows(a, b) {
        var av = rawValue(a, sortKey);
        var bv = rawValue(b, sortKey);
        if (av === null && bv === null) {
            return 0;
        }
        if (av === null) {
            return 1;
        }
        if (bv === null) {
            return -1;
        }
        if (typeof av === "string") {
            return collator.compare(av, bv) * sortDirection;
        }
        return (av - bv) * sortDirection;
    }

    function applySort() {
        if (!sortKey) {
            return;
        }
        var rows = dataRows().sort(compareRows);
        rows.forEach(function (row) {
            tbody.appendChild(row);
        });
        if (emptyRow) {
            tbody.appendChild(emptyRow);
        }
    }

    sortButtons.forEach(function (button) {
        button.addEventListener("click", function () {
            var key = button.dataset.sort;
            if (sortKey === key) {
                sortDirection = -sortDirection;
            } else {
                sortKey = key;
                sortDirection = 1;
            }
            sortButtons.forEach(function (other) {
                other.classList.remove("sort-asc", "sort-desc");
            });
            button.classList.add(sortDirection === 1 ? "sort-asc" : "sort-desc");
            applySort();
            applyFilter();
        });
    });

    if (searchInput) {
        searchInput.addEventListener("input", applyFilter);
        searchInput.addEventListener("search", applyFilter);
    }

    applyFilter();
})();
