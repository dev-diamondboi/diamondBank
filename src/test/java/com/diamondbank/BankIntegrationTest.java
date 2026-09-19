package com.diamondbank;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55432/diamond_bank_test}")
@AutoConfigureMockMvc
class BankIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired BankService bank;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String email;
    final List<String> createdEmails=new ArrayList<>();
    final String password="Portfolio-test-password-42";

    @BeforeEach void customer() {
        email="test-"+UUID.randomUUID()+"@example.com";
        createdEmails.add(email);
        bank.register(new BankController.Registration("Test Customer",email,password));
    }
    @AfterEach void cleanup() {
        for(String createdEmail:createdEmails) {
            UUID id=db.queryForObject("SELECT id FROM customers WHERE email=?",UUID.class,createdEmail);
            for(String table:List.of("operations","ledger_entries","goals","accounts"))db.update("DELETE FROM "+table+" WHERE customer_id=?",id);
            db.update("DELETE FROM customers WHERE id=?",id);
        }
    }
    BankController.Action transfer(String amount) { return new BankController.Action("transfer",null,Map.of("from","checking","to","savings","amount",amount)); }
    long balance(String account) { return db.queryForObject("SELECT a.balance FROM accounts a JOIN customers c ON c.id=a.customer_id WHERE c.email=? AND a.kind=?",Long.class,email,account); }

    @Test void authenticationAndCsrfAreRequired() throws Exception {
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/actions").with(user(email)).header("Idempotency-Key",UUID.randomUUID())
            .contentType("application/json").content(json.writeValueAsString(transfer("1")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/login").with(csrf()).param("username",email).param("password","wrong")).andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/api/login").with(csrf()).param("username",email).param("password",password))
            .andExpect(status().isNoContent()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)login.getRequest().getSession(false);
        mvc.perform(get("/api/state").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));
        mvc.perform(post("/api/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
    }
    @Test void registrationValidatesPasswordsAndDuplicates() throws Exception {
        mvc.perform(post("/api/register").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(new BankController.Registration("Test",email,"short")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/register").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(new BankController.Registration("Test",email,password)))).andExpect(status().isConflict());
        String hash=db.queryForObject("SELECT password_hash FROM customers WHERE email=?",String.class,email);
        assertNotEquals(password,hash);assertTrue(hash.startsWith("$2a$"));
    }
    @Test void transferConservesFundsAndReconcilesLedger() throws Exception {
        mvc.perform(post("/api/actions").with(user(email)).with(csrf()).header("Idempotency-Key",UUID.randomUUID())
            .contentType("application/json").content(json.writeValueAsString(transfer("12.34"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accounts.checking.balance").value(822816));
        assertEquals(2666050,balance("checking")+balance("savings"));
        for(String account:List.of("checking","savings")) {
            long ledger=db.queryForObject("SELECT SUM(l.amount) FROM ledger_entries l JOIN customers c ON c.id=l.customer_id WHERE c.email=? AND l.account=?",Long.class,email,account);
            assertEquals(balance(account),ledger);
        }
    }
    @Test void duplicateRequestIsAppliedExactlyOnce() {
        UUID key=UUID.randomUUID();bank.act(email,key,transfer("10"));bank.act(email,key,transfer("10"));
        assertEquals(823050,balance("checking"));
        assertThrows(IllegalArgumentException.class,()->bank.act(email,key,transfer("20")));
    }
    @Test void overdraftsFractionalCentsAndAllocatedSavingsAreRejected() {
        for(String value:List.of("10000","-1","0","0.001","NaN"))
            assertThrows(IllegalArgumentException.class,()->bank.act(email,UUID.randomUUID(),transfer(value)));
        assertThrows(IllegalArgumentException.class,()->bank.act(email,UUID.randomUUID(),new BankController.Action("payment",null,Map.of("from","savings","amount","6000","name","Test"))));
        assertEquals(824050,balance("checking"));assertEquals(1842000,balance("savings"));
    }
    @Test void concurrentWithdrawalsCannotOverdraw() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<Boolean> attempt=()->{ready.countDown();start.await();try{bank.act(email,UUID.randomUUID(),transfer("5000"));return true;}catch(IllegalArgumentException e){return false;}};
            var a=executor.submit(attempt);var b=executor.submit(attempt);ready.await();start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(324050,balance("checking"));assertEquals(2666050,balance("checking")+balance("savings"));
    }
    @Test void goalsBelongOnlyToTheirCustomer() {
        String other="test-"+UUID.randomUUID()+"@example.com";
        createdEmails.add(other);
        bank.register(new BankController.Registration("Other",other,password));
        UUID goal=db.queryForObject("SELECT g.id FROM goals g JOIN customers c ON c.id=g.customer_id WHERE c.email=? LIMIT 1",UUID.class,other);
        assertThrows(IllegalArgumentException.class,()->bank.act(email,UUID.randomUUID(),new BankController.Action("fund",goal.toString(),Map.of("amount","1"))));
        bank.act(email,UUID.randomUUID(),transfer("1"));
        assertEquals(824050,db.queryForObject("SELECT a.balance FROM accounts a JOIN customers c ON c.id=a.customer_id WHERE c.email=? AND a.kind='checking'",Long.class,other));
    }
    @Test void goalsCardsProfileAndResetPersist() {
        bank.act(email,UUID.randomUUID(),new BankController.Action("goal",null,Map.of("name","New laptop","target","2000")));
        UUID goal=db.queryForObject("SELECT g.id FROM goals g JOIN customers c ON c.id=g.customer_id WHERE c.email=? AND g.name='New laptop'",UUID.class,email);
        bank.act(email,UUID.randomUUID(),new BankController.Action("fund",goal.toString(),Map.of("amount","25")));
        assertEquals(2500,db.queryForObject("SELECT saved FROM goals WHERE id=?",Long.class,goal));
        bank.act(email,UUID.randomUUID(),new BankController.Action("freeze",null,Map.of()));
        assertEquals(true,bank.state(email).get("frozen"));
        bank.act(email,UUID.randomUUID(),new BankController.Action("profile",null,Map.of("name","New Name","email",email)));
        assertEquals("New Name",((Map<?,?>)bank.state(email).get("user")).get("name"));
        bank.act(email,UUID.randomUUID(),new BankController.Action("reset",null,Map.of()));
        assertEquals(false,bank.state(email).get("frozen"));assertEquals(824050,balance("checking"));
    }
}
