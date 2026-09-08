package com.shoppilot.bizmock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 买家身份平台级唯一（ADR 0004），因此本表不带归属列。 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "nickname", nullable = false, length = 64)
    private String nickname;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    protected Customer() {
    }

    public Customer(String id, String nickname, String phone) {
        this.id = id;
        this.nickname = nickname;
        this.phone = phone;
    }

    public String getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public String getPhone() {
        return phone;
    }
}
