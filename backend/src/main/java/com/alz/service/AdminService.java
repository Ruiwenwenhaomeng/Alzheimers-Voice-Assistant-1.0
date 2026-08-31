package com.alz.service;

import com.alz.entity.User;
import java.util.List;

public interface AdminService {

    List<User> listAllUsers();

    void deleteUser(Long id);
}