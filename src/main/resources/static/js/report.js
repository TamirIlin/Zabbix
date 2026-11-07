(function () {
    "use strict";

    var cfg = window.REPORT || {};
    var form = document.getElementById("reportForm");
    var fromInput = document.getElementById("fromDate");
    var toInput = document.getElementById("toDate");
    var showButton = document.getElementById("showBtn");
    var alertBox = document.getElementById("reportAlert");
    var loadingBox = document.getElementById("reportLoading");
    var results = document.getElementById("reportResults");
    var cardsContainer = document.getElementById("reportCards");
    var pdfMeta = document.getElementById("pdfMeta");
    var pdfButton = document.getElementById("downloadPdf");

    var charts = [];
    var currentReport = null;

    var numberFormat = new Intl.NumberFormat("ru-RU", {maximumFractionDigits: 2});
    var dateFormat = new Intl.DateTimeFormat("ru-RU", {day: "2-digit", month: "2-digit", year: "numeric"});
    var timeFormat = new Intl.DateTimeFormat("ru-RU", {hour: "2-digit", minute: "2-digit", second: "2-digit"});
    var fullFormat = new Intl.DateTimeFormat("ru-RU", {
        day: "2-digit", month: "2-digit", year: "numeric",
        hour: "2-digit", minute: "2-digit"
    });

    function pad(value) {
        return value < 10 ? "0" + value : String(value);
    }

    function toInputValue(epochSeconds) {
        var date = new Date(epochSeconds * 1000);
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
            + "T" + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }

    function parseInputValue(value) {
        if (!value) {
            return NaN;
        }
        var parsed = new Date(value);
        return Math.floor(parsed.getTime() / 1000);
    }

    function formatNumber(value) {
        return numberFormat.format(value);
    }

    function formatWithUnit(value, unit) {
        return formatNumber(value) + " " + unit;
    }

    function showAlert(message) {
        if (!alertBox) {
            return;
        }
        alertBox.hidden = false;
        alertBox.textContent = message;
    }

    function hideAlert() {
        if (alertBox) {
            alertBox.hidden = true;
        }
    }

    function setLoading(loading) {
        if (loadingBox) {
            loadingBox.hidden = !loading;
        }
        if (showButton) {
            showButton.disabled = loading;
        }
    }

    function selectedMetrics() {
        return Array.prototype.slice.call(document.querySelectorAll('input[name="metrics"]:checked'))
            .map(function (input) {
                return input.value;
            });
    }

    function metricTitles(keys) {
        var titles = {
            cpu: "процессор",
            memory: "память",
            disk: "диск",
            network: "сеть"
        };
        return keys.map(function (key) {
            return titles[key] || key;
        }).join(", ");
    }

    function currentSpan() {
        if (!currentReport) {
            return 24 * 3600;
        }
        return Math.max(60, currentReport.to - currentReport.from);
    }

    function formatTick(value) {
        var date = new Date(value);
        if (currentSpan() > 3 * 24 * 3600) {
            return pad(date.getDate()) + "." + pad(date.getMonth() + 1);
        }
        return pad(date.getHours()) + ":" + pad(date.getMinutes());
    }

    function hexToRgba(hex, alpha) {
        var source = (hex || "#2563eb").replace("#", "");
        if (source.length === 3) {
            source = source.split("").map(function (char) {
                return char + char;
            }).join("");
        }
        var number = parseInt(source, 16);
        var r = (number >> 16) & 255;
        var g = (number >> 8) & 255;
        var b = number & 255;
        return "rgba(" + r + ", " + g + ", " + b + ", " + alpha + ")";
    }

    function addSummaryChip(container, label, value) {
        var chip = document.createElement("span");
        chip.className = "summary-chip";
        var text = document.createElement("span");
        text.textContent = label;
        var strong = document.createElement("b");
        strong.textContent = value;
        chip.appendChild(text);
        chip.appendChild(strong);
        container.appendChild(chip);
    }

    function buildCard(series) {
        var card = document.createElement("article");
        card.className = "report-card";

        var head = document.createElement("div");
        head.className = "report-card-head";

        var dot = document.createElement("span");
        dot.className = "metric-dot";
        dot.style.background = series.color || "#2563eb";
        dot.style.boxShadow = "0 0 0 4px " + hexToRgba(series.color, 0.14);

        var title = document.createElement("h3");
        title.textContent = series.title;

        var unit = document.createElement("span");
        unit.className = "report-card-unit";
        unit.textContent = series.unit;

        head.appendChild(dot);
        head.appendChild(title);
        head.appendChild(unit);
        card.appendChild(head);

        var points = series.points || [];

        if (!points.length) {
            var empty = document.createElement("div");
            empty.className = "empty-series";
            empty.textContent = "Нет данных за выбранный период. Попробуйте изменить интервал или проверить наличие метрики в Zabbix.";
            card.appendChild(empty);
            return {card: card, canvas: null, series: series};
        }

        var chartWrap = document.createElement("div");
        chartWrap.className = "chart-wrap";
        var canvas = document.createElement("canvas");
        chartWrap.appendChild(canvas);
        card.appendChild(chartWrap);

        var summary = document.createElement("div");
        summary.className = "summary-row";
        addSummaryChip(summary, "Минимум", formatWithUnit(series.min, series.unit));
        addSummaryChip(summary, "Максимум", formatWithUnit(series.max, series.unit));
        addSummaryChip(summary, "Среднее", formatWithUnit(series.avg, series.unit));
        addSummaryChip(summary, "Точек", String(series.count));
        card.appendChild(summary);

        var tableWrap = document.createElement("div");
        tableWrap.className = "report-table-wrap";
        var table = document.createElement("table");
        table.className = "report-table";

        var thead = document.createElement("thead");
        var headRow = document.createElement("tr");
        ["Дата", "Время", "Значение, " + series.unit].forEach(function (label, index) {
            var th = document.createElement("th");
            th.textContent = label;
            if (index === 2) {
                th.style.textAlign = "right";
            }
            headRow.appendChild(th);
        });
        thead.appendChild(headRow);
        table.appendChild(thead);

        var tbody = document.createElement("tbody");
        var fragment = document.createDocumentFragment();
        points.forEach(function (point) {
            var date = new Date(point.t * 1000);
            var row = document.createElement("tr");

            var dateCell = document.createElement("td");
            dateCell.textContent = dateFormat.format(date);
            var timeCell = document.createElement("td");
            timeCell.textContent = timeFormat.format(date);
            var valueCell = document.createElement("td");
            valueCell.className = "value-cell";
            valueCell.textContent = formatNumber(point.v);

            row.appendChild(dateCell);
            row.appendChild(timeCell);
            row.appendChild(valueCell);
            fragment.appendChild(row);
        });
        tbody.appendChild(fragment);
        table.appendChild(tbody);
        tableWrap.appendChild(table);
        card.appendChild(tableWrap);

        return {card: card, canvas: canvas, series: series};
    }

    function createChart(canvas, series) {
        var points = (series.points || []).map(function (point) {
            return {x: point.t * 1000, y: point.v};
        });
        var chart = new Chart(canvas.getContext("2d"), {
            type: "line",
            data: {
                datasets: [{
                    label: series.title,
                    data: points,
                    borderColor: series.color,
                    backgroundColor: hexToRgba(series.color, 0.1),
                    borderWidth: 2,
                    pointRadius: 0,
                    pointHoverRadius: 4,
                    pointHoverBackgroundColor: series.color,
                    pointHoverBorderColor: "#ffffff",
                    pointHoverBorderWidth: 2,
                    fill: true,
                    tension: 0.25
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                interaction: {
                    mode: "nearest",
                    axis: "x",
                    intersect: false
                },
                plugins: {
                    legend: {display: false},
                    tooltip: {
                        backgroundColor: "rgba(15, 23, 42, 0.92)",
                        padding: 10,
                        cornerRadius: 8,
                        displayColors: false,
                        callbacks: {
                            title: function (items) {
                                return fullFormat.format(new Date(items[0].parsed.x));
                            },
                            label: function (context) {
                                return formatWithUnit(context.parsed.y, series.unit);
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        type: "linear",
                        grid: {display: false},
                        border: {display: false},
                        ticks: {
                            maxTicksLimit: 8,
                            color: "#94a3b8",
                            font: {size: 11},
                            callback: function (value) {
                                return formatTick(value);
                            }
                        }
                    },
                    y: {
                        beginAtZero: true,
                        suggestedMax: series.unit === "%" ? 100 : undefined,
                        grid: {color: "#eef2f8"},
                        border: {display: false},
                        ticks: {
                            color: "#94a3b8",
                            font: {size: 11},
                            callback: function (value) {
                                return formatNumber(value);
                            }
                        }
                    }
                }
            }
        });
        charts.push(chart);
    }

    function destroyCharts() {
        charts.forEach(function (chart) {
            try {
                chart.destroy();
            } catch (ignored) {
                // График уже мог быть удалён при перерисовке.
            }
        });
        charts = [];
    }

    function render(report) {
        currentReport = report;
        destroyCharts();
        cardsContainer.innerHTML = "";

        var metricsLabel = metricTitles(report.metrics.map(function (series) {
            return series.key;
        }));
        if (pdfMeta) {
            pdfMeta.textContent = "Период: " + fullFormat.format(new Date(report.from * 1000))
                + " — " + fullFormat.format(new Date(report.to * 1000))
                + " · Метрики: " + metricsLabel;
        }

        var pending = [];
        report.metrics.forEach(function (series) {
            var built = buildCard(series);
            cardsContainer.appendChild(built.card);
            if (built.canvas) {
                pending.push(built);
            }
        });
        pending.forEach(function (built) {
            createChart(built.canvas, built.series);
        });

        results.hidden = false;
    }

    function loadReport(event) {
        if (event) {
            event.preventDefault();
        }
        hideAlert();

        var from = parseInputValue(fromInput.value);
        var to = parseInputValue(toInput.value);
        var metrics = selectedMetrics();

        if (!Number.isFinite(from) || !Number.isFinite(to)) {
            showAlert("Укажите корректные даты начала и окончания периода.");
            return;
        }
        if (from >= to) {
            showAlert("Дата «от» должна быть раньше даты «до».");
            return;
        }
        if (!metrics.length) {
            showAlert("Выберите хотя бы одну метрику для отчёта.");
            return;
        }

        setLoading(true);
        if (results) {
            results.hidden = true;
        }

        var url = "/api/servers/" + encodeURIComponent(cfg.hostId) + "/report"
            + "?from=" + from + "&to=" + to + "&metrics=" + encodeURIComponent(metrics.join(","));

        fetch(url, {headers: {"Accept": "application/json"}})
            .then(function (response) {
                return response.json().catch(function () {
                    return null;
                }).then(function (data) {
                    if (!response.ok || !data || data.success === false) {
                        throw new Error((data && data.error) || "Не удалось построить отчёт.");
                    }
                    return data.report;
                });
            })
            .then(function (report) {
                render(report);
            })
            .catch(function (error) {
                showAlert(error && error.message
                    ? error.message
                    : "Не удалось построить отчёт. Проверьте подключение к Zabbix и повторите попытку.");
            })
            .finally(function () {
                setLoading(false);
            });
    }

    function addCanvasToPdf(pdf, canvas, cursor, margin, pageWidth, pageHeight) {
        var contentWidth = pageWidth - margin * 2;
        var ratio = contentWidth / canvas.width;
        var totalHeight = canvas.height * ratio;
        var availableHeight = pageHeight - margin * 2;
        var sourceY = 0;

        while (sourceY < canvas.height) {
            if (cursor + 6 > pageHeight - margin) {
                pdf.addPage();
                cursor = margin;
            }
            var spaceLeft = pageHeight - margin - cursor;
            var remainingHeight = totalHeight - sourceY * ratio;
            var sliceHeight = Math.min(remainingHeight, availableHeight, spaceLeft);
            if (sliceHeight <= 1) {
                pdf.addPage();
                cursor = margin;
                continue;
            }
            var sourceHeight = Math.min(canvas.height - sourceY, Math.ceil(sliceHeight / ratio));
            var piece = document.createElement("canvas");
            piece.width = canvas.width;
            piece.height = sourceHeight;
            var context = piece.getContext("2d");
            context.fillStyle = "#ffffff";
            context.fillRect(0, 0, piece.width, piece.height);
            context.drawImage(canvas, 0, sourceY, canvas.width, sourceHeight,
                0, 0, canvas.width, sourceHeight);

            var imageHeight = sourceHeight * ratio;
            pdf.addImage(piece.toDataURL("image/jpeg", 0.93), "JPEG",
                margin, cursor, contentWidth, imageHeight, undefined, "FAST");
            cursor += imageHeight + 5;
            sourceY += sourceHeight;
        }
        return cursor;
    }

    function safeFileName(value) {
        return String(value || "server").replace(/[\\/:*?"<>|]+/g, "-").replace(/\s+/g, "-");
    }

    function downloadPdf() {
        if (!currentReport || !window.html2canvas || !window.jspdf) {
            showAlert("Не удалось загрузить модуль формирования PDF. Обновите страницу и повторите попытку.");
            return;
        }

        var originalText = pdfButton.innerHTML;
        pdfButton.disabled = true;
        pdfButton.textContent = "Формируем PDF…";
        document.body.classList.add("pdf-export");

        setTimeout(function () {
            var jsPdf = window.jspdf.jsPDF;
            var pdf = new jsPdf({orientation: "p", unit: "mm", format: "a4", compress: true});
            var pageWidth = pdf.internal.pageSize.getWidth();
            var pageHeight = pdf.internal.pageSize.getHeight();
            var margin = 10;
            var cursor = margin;

            var header = document.querySelector(".pdf-heading");
            var captures = [];
            if (header) {
                captures.push(header);
            }
            Array.prototype.slice.call(document.querySelectorAll("#reportCards .report-card"))
                .forEach(function (card) {
                    captures.push(card);
                });

            var chain = Promise.resolve();
            captures.forEach(function (element) {
                chain = chain.then(function () {
                    return window.html2canvas(element, {
                        scale: 2,
                        backgroundColor: "#ffffff",
                        useCORS: true,
                        logging: false
                    }).then(function (canvas) {
                        cursor = addCanvasToPdf(pdf, canvas, cursor, margin, pageWidth, pageHeight);
                    });
                });
            });

            chain.then(function () {
                var fileName = "report-" + safeFileName(cfg.hostName) + "-"
                    + new Date().toISOString().slice(0, 10) + ".pdf";
                pdf.save(fileName);
            }).catch(function () {
                showAlert("Не удалось сформировать PDF. Попробуйте ещё раз или уменьшите период отчёта.");
            }).finally(function () {
                document.body.classList.remove("pdf-export");
                pdfButton.disabled = false;
                pdfButton.innerHTML = originalText;
            });
        }, 80);
    }

    if (form) {
        form.addEventListener("submit", loadReport);
    }
    if (pdfButton) {
        pdfButton.addEventListener("click", downloadPdf);
    }

    var defaultFrom = cfg.defaultFrom || Math.floor(Date.now() / 1000) - 24 * 3600;
    var defaultTo = cfg.defaultTo || Math.floor(Date.now() / 1000);
    if (fromInput) {
        fromInput.value = toInputValue(defaultFrom);
    }
    if (toInput) {
        toInput.value = toInputValue(defaultTo);
    }

    if (typeof Chart === "undefined") {
        showAlert("Не удалось загрузить библиотеку графиков. Проверьте подключение и обновите страницу.");
        return;
    }

    loadReport();
})();
