package com.example.Zabbix.service;

public class ZabbixApiException extends RuntimeException {

    private final String userMessage;

    public ZabbixApiException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    public ZabbixApiException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
