package com.jrbin;

import comp9322.assignment1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;

@Controller
@RequestMapping("/driver")
public class DriverController {

    private static final Logger logger = LoggerFactory.getLogger(DriverController.class);

    @Autowired
    private RestService driverRestService;

    @Autowired
    private EmployeeValidationService soapService;

    @Autowired
    private ObjectFactory soapFactory;

    @GetMapping("/process")
    public String driverMain(@RequestParam int renewalId, Model model) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal == null || renewal.getStatus() == Status.ARCHIVED) {
            throw new NotFoundException(String.format("Renewal notice %d not found", renewalId));
        }
        License license = driverRestService.getLicense(renewal.getLicense().getId()).execute().body();
        Payment payment = driverRestService.getPayment(renewal.getPayment().getId()).execute().body();
        renewal.setLicense(license);
        renewal.setPayment(payment);
        model.addAttribute("renewal", renewal);
        model.addAttribute("expiryNew", getExpiryNew(license.getExpiryDate()));
        model.addAttribute("expiryExtended", getExpiryExtended(license.getExpiryDate()));
        return "driver/driver.jsp";
    }

    @PostMapping("/welcome")
    public String driverWelcome(@RequestParam int renewalId) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal.getStatus() != Status.NEW) {
            throw new UnauthorizedException("You are not in a valid state");
        }
        renewal.setStatus(Status.CONFIRMING);
        driverRestService.updateRenewal(renewalId, renewal).execute();
        return String.format("redirect:/driver/process?renewalId=%d", renewalId);
    }

    @PostMapping("/confirm")
    public String driverConfirm(@RequestParam int renewalId, @RequestParam int action) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal.getStatus() != Status.CONFIRMING) {
            throw new UnauthorizedException("You are not in a valid state");
        }
        if (action == 0) {
            renewal.setStatus(Status.UPDATING);
        } else {
            renewal.setStatus(Status.CONFIRMED);
            License license = driverRestService.getLicense(renewal.getLicense().getId()).execute().body();
            renewal.setEmail(license.getEmail());
            renewal.setAddress(license.getAddress());
            renewal.setReviewCode(ReviewCode.DEFAULT);
        }
        driverRestService.updateRenewal(renewalId, renewal).execute();
        return String.format("redirect:/driver/process?renewalId=%d", renewalId);
    }

    @PostMapping("/update")
    public String driverUpdate(
            @RequestParam int renewalId, @RequestParam int action,
            @RequestParam String email, @RequestParam String preStreet, @RequestParam String streetName,
            @RequestParam String streetType, @RequestParam String suburb, @RequestParam String state) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal.getStatus() != Status.UPDATING) {
            throw new UnauthorizedException("You are not in a valid state");
        }
        if (action == 0) {
            renewal.setStatus(Status.CONFIRMING);
        } else {
            CheckEmailAddressRequest emailRequest = soapFactory.createCheckEmailAddressRequest();
            CheckAddressRequest addressRequest = soapFactory.createCheckAddressRequest();
            ReturnPostcodeRequest postcodeRequest = soapFactory.createReturnPostcodeRequest();

            emailRequest.setEmail(email);

            addressRequest.setPreStreet(preStreet);
            addressRequest.setStreetName(streetName);
            addressRequest.setStreetType(streetType);
            addressRequest.setSuburb(suburb);
            addressRequest.setState(state);

            postcodeRequest.setSuburb(suburb);
            postcodeRequest.setState(state);

            CheckEmailAddressResponse emailResponse = soapService.checkEmailAddress(emailRequest);
            boolean emailValid = emailResponse.isValue();

            boolean addressValid = false;
            String newAddress = String.format("%s %s %s, %s, %s", preStreet, streetName, streetType, suburb, state);
            try {
                CheckAddressResponse addressResponse = soapService.checkAddress(addressRequest);
                ReturnPostcodeResponse postcodeResponse = soapService.returnPostcode(postcodeRequest);
                newAddress = addressResponse.getExactAddress() + " " + postcodeResponse.getPostcode();
                addressValid = true;
            } catch (ValidationFaultMsg e) {
                logger.debug("validation fault: {}", e.getFaultInfo().getErrtext());
            }

            renewal.setEmail(email);
            renewal.setAddress(newAddress);
            if (emailValid && addressValid) {
                renewal.setStatus(Status.CONFIRMED);
                renewal.setReviewCode(ReviewCode.DEFAULT);
            } else if (!emailValid && !addressValid) {
                renewal.setStatus(Status.PENDING);
                renewal.setReviewCode(ReviewCode.INVALID_BOTH);
            } else if (!emailValid && addressValid) {
                renewal.setStatus(Status.PENDING);
                renewal.setReviewCode(ReviewCode.INVALID_EMAIL);
            } else {
                renewal.setStatus(Status.PENDING);
                renewal.setReviewCode(ReviewCode.INVALID_ADDRESS);
            }
        }
        driverRestService.updateRenewal(renewalId, renewal).execute();
        return String.format("redirect:/driver/process?renewalId=%d", renewalId);
    }

    @PostMapping("/extension")
    public String driverExtension(@RequestParam int renewalId, @RequestParam int action) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal.getStatus() != Status.CONFIRMED) {
            throw new UnauthorizedException("You are not in a valid state");
        }
        if (action == 0) {
            renewal.setStatus(Status.APPROVED);
            renewal.setReviewCode(ReviewCode.DEFAULT);
        } else if (action == 1) {
            renewal.setStatus(Status.PENDING);
            renewal.setReviewCode(ReviewCode.EXTRA_EXTENSION);
        } else if (action == 2) {
            renewal.setStatus(Status.CONFIRMING);
        }
        driverRestService.updateRenewal(renewalId, renewal).execute();
        return String.format("redirect:/driver/process?renewalId=%d", renewalId);
    }

    @PostMapping("/back")
    public String driverExtension(@RequestParam int renewalId) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal.getStatus() != Status.CONFIRMED) {
            throw new UnauthorizedException("You are not in a valid state");
        }
        renewal.setStatus(Status.CONFIRMING);
        driverRestService.updateRenewal(renewalId, renewal).execute();
        return String.format("redirect:/driver/process?renewalId=%d", renewalId);
    }

    @PostMapping("/pay")
    public String driverPay(@RequestParam int renewalId) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal.getStatus() != Status.APPROVED) {
            throw new UnauthorizedException("You are not in a valid state");
        }
        Payment payment = renewal.getPayment();
        payment.setPaidDate(new Date());
        driverRestService.updatePayment(payment.getId(), payment).execute();
        License license = renewal.getLicense();
        Date expiryDate = null;
        if (renewal.getReviewCode() == ReviewCode.EXTRA_EXTENSION) {
            expiryDate = getExpiryExtended(license.getExpiryDate());
        } else {
            expiryDate = getExpiryNew(license.getExpiryDate());
        }
        license.setExpiryDate(expiryDate);
        license.setEmail(renewal.getEmail());
        license.setAddress(renewal.getAddress());
        driverRestService.updateLicense(license.getId(), license).execute();
        renewal.setStatus(Status.SUCCESSFUL);
        driverRestService.updateRenewal(renewalId, renewal).execute();
        return String.format("redirect:/driver/process?renewalId=%d", renewalId);
    }

    @PostMapping("/archive")
    public String driverArchive(@RequestParam int renewalId) throws IOException {
        Renewal renewal = driverRestService.getRenewal(renewalId).execute().body();
        if (renewal.getStatus() != Status.SUCCESSFUL) {
            throw new UnauthorizedException("You are not in a valid state");
        }
        renewal.setStatus(Status.ARCHIVED);
        driverRestService.updateRenewal(renewalId, renewal).execute();
        return String.format("redirect:/driver/process?renewalId=%d", renewalId);
    }

    private Date getExpiryNew(Date expiryOld) {
        Calendar c = Calendar.getInstance();
        c.setTime(expiryOld);
        c.add(Calendar.YEAR, 5);
        return c.getTime();
    }

    private Date getExpiryExtended(Date expiryOld) {
        Calendar c = Calendar.getInstance();
        c.setTime(expiryOld);
        c.add(Calendar.YEAR, 10);
        return c.getTime();
    }
}
