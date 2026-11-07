(function () {
    "use strict";

    var tokenInput = document.getElementById("apiToken");
    var toggle = document.getElementById("tokenToggle");
    var testButton = document.getElementById("testBtn");
    var result = document.getElementById("testResult");
    var urlInput = document.getElementById("zabbixUrl");

    if (toggle && tokenInput) {
        toggle.addEventListener("click", function () {
            var show = tokenInput.type === "password";
            tokenInput.type = show ? "text" : "password";
            toggle.setAttribute("aria-label", show ? "Скрыть токен" : "Показать токен");
            var open = toggle.querySelector(".eye-open");
            var closed = toggle.querySelector(".eye-closed");
            if (open && closed) {
                open.hidden = show;
                closed.hidden = !show;
            }
        });
    }

    function showResult(message, success) {
        if (!result) {
            return;
        }
        result.hidden = false;
        result.className = "alert " + (success ? "alert-success" : "alert-error");
        result.textContent = message;
    }

    if (testButton) {
        testButton.addEventListener("click", function () {
            var url = urlInput ? urlInput.value.trim() : "";
            var token = tokenInput ? tokenInput.value.trim() : "";

            if (!url) {
                showResult("Введите адрес сервера Zabbix.", false);
                if (urlInput) {
                    urlInput.focus();
                }
                return;
            }
            if (!token) {
                showResult("Введите API-токен Zabbix.", false);
                if (tokenInput) {
                    tokenInput.focus();
                }
                return;
            }

            var originalText = testButton.innerHTML;
            testButton.disabled = true;
            testButton.textContent = "Проверяем подключение…";
            if (result) {
                result.hidden = true;
            }

            var formData = new FormData();
            formData.append("zabbixUrl", url);
            formData.append("apiToken", token);

            fetch("/api/settings/test", {
                method: "POST",
                body: formData,
                headers: {"Accept": "application/json"}
            })
                .then(function (response) {
                    return response.json();
                })
                .then(function (data) {
                    showResult(data.message || "Не удалось проверить подключение.", !!data.success);
                })
                .catch(function () {
                    showResult("Не удалось проверить подключение. Проверьте адрес Zabbix и доступность сети.", false);
                })
                .finally(function () {
                    testButton.disabled = false;
                    testButton.innerHTML = originalText;
                });
        });
    }
})();
