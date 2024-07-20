package com.sq022groupA.escalayt.service;

import com.sq022groupA.escalayt.payload.request.*;
import com.sq022groupA.escalayt.payload.response.LoginResponse;
import jakarta.mail.MessagingException;

public interface AdminService {

    String register(AdminRequest registrationRequest) throws MessagingException;
    LoginResponse loginUser(LoginRequestDto loginRequestDto);
    void resetPassword(PasswordResetDto passwordResetDto);
    void newResetPassword(PasswordResetDto passwordResetDto);
    String editUserDetails(String username, UserDetailsDto userDetailsDto);


    String forgotPassword (ForgetPasswordDto forgetPasswordDto);

}