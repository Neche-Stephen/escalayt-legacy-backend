package com.sq022groupA.escalayt.service.impl;

import com.sq022groupA.escalayt.config.JwtService;
import com.sq022groupA.escalayt.entity.model.*;
import com.sq022groupA.escalayt.payload.response.*;
import com.sq022groupA.escalayt.repository.*;
import com.sq022groupA.escalayt.exception.PasswordsDoNotMatchException;
import com.sq022groupA.escalayt.exception.UserNotFoundException;
import com.sq022groupA.escalayt.exception.UsernameAlreadyExistsException;
import com.sq022groupA.escalayt.payload.request.*;
import com.sq022groupA.escalayt.service.EmailService;
import com.sq022groupA.escalayt.service.AdminService;
import com.sq022groupA.escalayt.utils.ForgetPasswordEmailBody;
import com.sq022groupA.escalayt.utils.UserRegistrationEmailBody;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final AdminRepository adminRepository;
    private final UserRepository userRepository;
    private final JwtTokenRepository jwtTokenRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final ConfirmationTokenRepository confirmationTokenRepository;

    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;


    @Value("${baseUrl}")
    private String baseUrl;


    @Override
    public String register(AdminRequest registrationRequest) throws MessagingException {

        //Optional<User> existingUser = userRepository.findByEmail(registrationRequest.getEmail());
        Optional<Admin> existingUser = adminRepository.findByUsername(registrationRequest.getUserName());


        if(existingUser.isPresent()){
            throw new RuntimeException("Email already exists. Login to your account");
        }

        // check if username already exists
        Optional<Admin> existingUserByUsername = adminRepository.findByUsername(registrationRequest.getUserName());
        if (existingUserByUsername.isPresent()) {
            throw new UsernameAlreadyExistsException("Username already exists. Please choose another username.");
        }

        Optional<Role> userRole = roleRepository.findByName("ADMIN");
        if (userRole.isEmpty()) {
            throw new RuntimeException("Default role ADMIN not found in the database.");
        }

        Set<Role> roles = new HashSet<>();
        roles.add(userRole.get());


        Admin newUser = Admin.builder()
                .firstName(registrationRequest.getFirstName())
                .lastName(registrationRequest.getLastName())
                .username(registrationRequest.getUserName())
                .email(registrationRequest.getEmail())
                .phoneNumber(registrationRequest.getPhoneNumber())
                .password(passwordEncoder.encode(registrationRequest.getPassword()))
                .roles(roles)
                .build();

        Admin savedUser = adminRepository.save(newUser);

        ConfirmationToken confirmationToken = new ConfirmationToken(savedUser);
        confirmationTokenRepository.save(confirmationToken);
        System.out.println(confirmationToken.getToken());

//        String confirmationUrl = EmailTemplate.getVerificationUrl(baseUrl, confirmationToken.getToken());

//        String confirmationUrl = baseUrl + "/confirmation/confirm-token-sucess.html?token=" + confirmationToken.getToken();
        String confirmationUrl = "http://localhost:8080/api/v1/auth/confirm?token=" + confirmationToken.getToken();

//        send email alert
        EmailDetails emailDetails = EmailDetails.builder()
                .recipient(savedUser.getEmail())
                .subject("ACCOUNT CREATION SUCCESSFUL")
                .build();
        emailService.sendSimpleMailMessage(emailDetails, savedUser.getFirstName(), savedUser.getLastName(), confirmationUrl);
        return "Confirmed Email";

    }

    @Override
    public LoginResponse loginUser(LoginRequestDto loginRequestDto) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequestDto.getUsername(),
                        loginRequestDto.getPassword()
                )
        );
        Admin admin = adminRepository.findByUsername(loginRequestDto.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found with username: " + loginRequestDto.getUsername()));

        if (!admin.isEnabled()) {
            throw new RuntimeException("User account is not enabled. Please check your email to confirm your account.");
        }

        var jwtToken = jwtService.generateToken(admin);
        revokeAllUserTokens(admin);
        saveUserToken(admin, jwtToken);

        return LoginResponse.builder()
                .responseCode("002")
                .responseMessage("Login Successfully")
                .loginInfo(LoginInfo.builder()
                        .username(admin.getUsername())
                        .token(jwtToken)
                        .build())
                .build();
    }

    private void saveUserToken(Admin userModel, String jwtToken) {
        var token = JwtToken.builder()
                .admin(userModel)
                .token(jwtToken)
                .tokenType("BEARER")
                .expired(false)
                .revoked(false)
                .build();
        jwtTokenRepository.save(token);
    }

    private void revokeAllUserTokens(Admin adminModel) {
        var validUserTokens = jwtTokenRepository.findAllValidTokenByUser(adminModel.getId());
        if (validUserTokens.isEmpty())
            return;
        validUserTokens.forEach(token -> {
            token.setExpired(true);
            token.setRevoked(true);
        });
        jwtTokenRepository.saveAll(validUserTokens);
    }

    public void resetPassword(PasswordResetDto passwordResetDto) {

        if (!passwordResetDto.getNewPassword().equals(passwordResetDto.getConfirmPassword())) {
            throw new PasswordsDoNotMatchException("New password and confirm password do not match.");
        }

        Admin user = adminRepository.findByEmail(passwordResetDto.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + passwordResetDto.getEmail()));

        if(user.getResetToken() != null){
            return;
        }

        user.setPassword(passwordEncoder.encode(passwordResetDto.getNewPassword()));
        adminRepository.save(user);
    }

    @Override
    public void newResetPassword(PasswordResetDto passwordResetDto) {
        Admin admin = adminRepository.findByEmail(passwordResetDto.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + passwordResetDto.getEmail()));

        if(admin.getResetToken() != null){
            return;
        }

        admin.setPassword(passwordEncoder.encode(passwordResetDto.getNewPassword()));
        adminRepository.save(admin);
    }

    @Override
    public String editUserDetails(String username, UserDetailsDto userDetailsDto) {
        Admin admin = adminRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        //Update user details
        admin.setFirstName(userDetailsDto.getFirstName());
        admin.setLastName(userDetailsDto.getLastName());
        admin.setEmail(userDetailsDto.getEmail());
        admin.setPhoneNumber(userDetailsDto.getPhoneNumber());

        //save the updated user
        adminRepository.save(admin);

        return "User details updated successfully";
    }

    @Override
    public String forgotPassword(ForgetPasswordDto forgetPasswordDto) {


        /*
        steps
        1- check if email exist (settled)
        2- create a random token (done)
        3- Hash the token and add it to the db under the user (done)
        4- set expiration time for the token in the db (done)
        5- generate a reset url using the token (done)
        6- send email with reset url link
         */

        Optional<Admin> checkUser = adminRepository.findByEmail(forgetPasswordDto.getEmail());

        // check if user exist with that email
        if(!checkUser.isPresent()) throw new RuntimeException("No such user with this email.");

        Admin forgettingUser = checkUser.get();

        // generate a hashed token
        ConfirmationToken forgetPassWordToken = new ConfirmationToken(forgettingUser);

        // saved the token.
        // the token has an expiration date
        confirmationTokenRepository.save(forgetPassWordToken);
        // System.out.println("the token "+forgetPassWordToken.getToken());

        // generate a password reset url
        String resetPasswordUrl = "http://localhost:8080/api/v1/auth/confirm?token=" + forgetPassWordToken.getToken();



        // click this link to reset password;
        EmailDetails emailDetails = EmailDetails.builder()
                .recipient(forgettingUser.getEmail())
                .subject("FORGET PASSWORD")
                .messageBody(ForgetPasswordEmailBody.buildEmail(forgettingUser.getFirstName(),
                        forgettingUser.getLastName(), resetPasswordUrl))
                .build();

        //send the reset password link
        emailService.mimeMailMessage(emailDetails);

        return "A reset password link has been sent to your account." + resetPasswordUrl;
    }




    // USER/EMPLOYEE RELATED SERVICE IMPLEMENTATIONS \\


    // USER/EMPLOYEE REGISTRATION
    @Override
    public UserRegistrationResponse registerUser(String currentUsername, UserRegistrationDto userRegistrationDto) throws MessagingException {
        // GET ADMIN ID BY USERNAME
        Optional<Admin> loggedInAdmin = adminRepository.findByUsername(currentUsername);

        // Check if admin is present
        if (loggedInAdmin.isEmpty()) {
            throw new RuntimeException("Admin user not found");
        }

        // Check if the email already exists
        Optional<User> existingUser = userRepository.findByEmail(userRegistrationDto.getEmail());
        if (existingUser.isPresent()) {
            throw new RuntimeException("User email already exists");
        }

        Optional<Role> userRole = roleRepository.findByName("USER");
        if (userRole.isEmpty()) {
            throw new RuntimeException("Default role ADMIN not found in the database.");
        }

        Set<Role> roles = new HashSet<>();
        roles.add(userRole.get());

        // Build new User entity
        User newUser = User.builder()
                .fullName(userRegistrationDto.getFullName())
                .email(userRegistrationDto.getEmail())
                .phoneNumber(userRegistrationDto.getPhoneNumber())
                .jobTitle(userRegistrationDto.getJobTitle())
                .department(userRegistrationDto.getDepartment())
                .username(userRegistrationDto.getUsername())
                .password(passwordEncoder.encode(userRegistrationDto.getPassword()))
                .createdUnder(loggedInAdmin.get().getId())
                .roles(roles)
                .build();

        // Save new user to the repository
        User savedUser = userRepository.save(newUser);

        // Set up email message for the registered user/employee
        String userLoginUrl = baseUrl + "/user-login";

        EmailDetails emailDetails = EmailDetails.builder()
                .recipient(savedUser.getEmail())
                .subject("ACTIVATE YOUR ACCOUNT")
                .messageBody(UserRegistrationEmailBody.buildEmail(savedUser.getFullName(),
                        savedUser.getUsername(), userRegistrationDto.getPassword(), userLoginUrl))
                .build();

        // Send email message to the registered user/employee
        emailService.mimeMailMessage(emailDetails);

        // Method response
        return UserRegistrationResponse.builder()
                .responseTemplate(ResponseTemplate.builder()
                        .responseCode("007")
                        .responseMessage("User/Employee Created Successfully")
                        .build())
                .fullName(savedUser.getFullName())
                .username(savedUser.getUsername())
                .email(savedUser.getEmail())
                .phoneNumber(savedUser.getPhoneNumber())
                .jobTitle(savedUser.getJobTitle())
                .department(savedUser.getDepartment())
                .createdUnder(savedUser.getCreatedUnder())
                .build();
    }


}
