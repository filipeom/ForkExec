package com.forkexec.pts.domain;

public class User {

    private int points;
    private String email;

    public User(String email, int points) {
        this.email = email;
        this.points = points;
    }

    public synchronized int getPoints() {
        return this.points;
    } 

    public String getEmail() {
        return this.email;
    }

    public synchronized void setBalance(int points) {
        this.points = points;
    }

}