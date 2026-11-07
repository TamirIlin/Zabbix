package com.example.Zabbix.web;

import com.example.Zabbix.service.ResourceNotFoundException;
import com.example.Zabbix.service.ZabbixApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class PageExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PageExceptionHandler.class);

    @ExceptionHandler(ZabbixApiException.class)
    public ModelAndView handleZabbix(ZabbixApiException ex, HttpServletRequest request) {
        return errorView(request, "Не удалось получить данные из Zabbix",
                ex.getUserMessage(), HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ModelAndView handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return errorView(request, "Объект не найден", ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ModelAndView handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return errorView(request, "Некорректный запрос", ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Необработанная ошибка при обработке запроса {}", request.getRequestURI(), ex);
        return errorView(request, "Что-то пошло не так",
                "Не удалось выполнить запрос. Попробуйте обновить страницу или повторить попытку позже.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ModelAndView errorView(HttpServletRequest request, String title, String message, HttpStatus status) {
        ModelAndView view = new ModelAndView("error");
        view.setStatus(status);
        view.addObject("title", title);
        view.addObject("message", message);
        view.addObject("path", request.getRequestURI());
        return view;
    }
}
