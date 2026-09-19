package com.diamondbank;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankService {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    public BankService(JdbcTemplate db, PasswordEncoder passwords) { this.db=db; this.passwords=passwords; }

    @Transactional
    public void register(BankController.Registration request) {
        if(request.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password must fit within 72 UTF-8 bytes.");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO customers(id,email,password_hash,display_name) VALUES (?,?,?,?)", id,
            request.email().trim().toLowerCase(Locale.ROOT), passwords.encode(request.password()), text(request.name(),60));
        db.update("INSERT INTO accounts(customer_id,kind,name,number,balance) VALUES (?,?,?,?,0)",id,"checking","Everyday checking","4829");
        db.update("INSERT INTO accounts(customer_id,kind,name,number,balance) VALUES (?,?,?,?,0)",id,"savings","High-yield savings","9016");
        seed(id);
    }
    private UUID customer(String email, boolean lock) {
        return db.queryForObject("SELECT id FROM customers WHERE email=?"+(lock?" FOR UPDATE":""),UUID.class,email);
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> state(String email) { return snapshot(customer(email,false)); }

    private Map<String,Object> snapshot(UUID id) {
        Map<String,Object> user=db.queryForMap("SELECT display_name,email,frozen,notifications FROM customers WHERE id=?",id);
        Map<String,Object> accounts=new LinkedHashMap<>();
        db.query("SELECT kind,name,number,balance FROM accounts WHERE customer_id=? ORDER BY kind",rs -> {
            accounts.put(rs.getString("kind"),Map.of("name",rs.getString("name"),"number",rs.getString("number"),"balance",rs.getLong("balance")));
        }, id);
        var goals=db.query("SELECT id,name,target,saved FROM goals WHERE customer_id=? ORDER BY name",(rs,n)->
            Map.<String,Object>of("id",rs.getObject("id").toString(),"name",rs.getString("name"),"target",rs.getLong("target"),"saved",rs.getLong("saved"),"emoji","✧"),id);
        var transactions=db.query("SELECT * FROM ledger_entries WHERE customer_id=? ORDER BY created_at DESC,id DESC",(rs,n)->
            Map.<String,Object>of("id",rs.getObject("id").toString(),"name",rs.getString("name"),"category",rs.getString("category"),
                "amount",rs.getLong("amount"),"account",rs.getString("account"),"date",rs.getTimestamp("created_at").toInstant().toString(),"icon",rs.getLong("amount")>0?"↙":"↗"),id);
        return Map.of("user",Map.of("name",user.get("display_name"),"email",user.get("email")),"accounts",accounts,
            "goals",goals,"transactions",transactions,"frozen",user.get("frozen"),"notifications",user.get("notifications"));
    }

    // Locking one customer row serializes their account and goal mutations.
    // Balance checks, ledger entries and idempotency records commit together.
    @Transactional
    public Map<String,Object> act(String email, UUID key, BankController.Action action) {
        UUID customer=customer(email,true);
        Map<String,String> fields=action.fields()==null?Map.of():action.fields();
        String hash=hash(action.type(),action.id(),fields);
        var previous=db.queryForList("SELECT payload_hash FROM operations WHERE customer_id=? AND idempotency_key=?",String.class,customer,key);
        if(!previous.isEmpty()) {
            require(previous.getFirst().equals(hash),"This request key was already used for a different action.");
            return snapshot(customer);
        }
        switch(action.type()) {
            case "transfer", "payment" -> move(customer,key,fields,action.type().equals("transfer"));
            case "goal" -> db.update("INSERT INTO goals(id,customer_id,name,target,saved) VALUES (?,?,?,?,0)",UUID.randomUUID(),customer,text(fields.get("name"),60),cents(fields.get("target")));
            case "fund" -> {
                UUID goal;
                try { goal=UUID.fromString(action.id()); } catch(Exception e) { throw new IllegalArgumentException("Select a valid goal."); }
                var found=db.queryForList("SELECT target,saved FROM goals WHERE id=? AND customer_id=?",goal,customer);
                require(!found.isEmpty(),"Goal not found.");
                long amount=cents(fields.get("amount")), saved=((Number)found.getFirst().get("saved")).longValue(),target=((Number)found.getFirst().get("target")).longValue();
                require(amount<=target-saved,"This exceeds the amount remaining for this goal.");
                require(amount<=available(customer,"savings"),"Transfer more money to savings first.");
                db.update("UPDATE goals SET saved=saved+? WHERE id=? AND customer_id=?",amount,goal,customer);
            }
            case "freeze" -> db.update("UPDATE customers SET frozen=NOT frozen WHERE id=?",customer);
            case "notifications" -> db.update("UPDATE customers SET notifications=NOT notifications WHERE id=?",customer);
            case "profile" -> {
                require(email.equals(fields.get("email")),"Your sign-in email cannot be changed in this demo.");
                db.update("UPDATE customers SET display_name=? WHERE id=?",text(fields.get("name"),60),customer);
            }
            case "reset" -> {
                db.update("DELETE FROM goals WHERE customer_id=?",customer);
                db.update("DELETE FROM ledger_entries WHERE customer_id=?",customer);
                db.update("UPDATE accounts SET balance=0 WHERE customer_id=?",customer);
                db.update("UPDATE customers SET frozen=false,notifications=true WHERE id=?",customer);
                seed(customer);
            }
            default -> throw new IllegalArgumentException("Unknown action.");
        }
        db.update("INSERT INTO operations(customer_id,idempotency_key,payload_hash) VALUES (?,?,?)",customer,key,hash);
        return snapshot(customer);
    }
    private void move(UUID customer, UUID operation, Map<String,String> fields, boolean transfer) {
        String from=fields.get("from"), to=fields.get("to");
        require(Set.of("checking","savings").contains(from==null?"":from),"Select a valid source account.");
        if(transfer) require(Set.of("checking","savings").contains(to==null?"":to)&&!from.equals(to),"Choose two different accounts.");
        long amount=cents(fields.get("amount"));
        require(amount<=available(customer,from),"Insufficient unallocated funds in this account.");
        String name=transfer?"Transfer to "+to:text(fields.get("name"),80);
        db.update("UPDATE accounts SET balance=balance-? WHERE customer_id=? AND kind=?",amount,customer,from);
        entry(customer,from,name,transfer?"Transfer":"Payment",-amount,operation);
        if(transfer) {
            db.update("UPDATE accounts SET balance=balance+? WHERE customer_id=? AND kind=?",amount,customer,to);
            entry(customer,to,"Transfer from "+from,"Transfer",amount,operation);
        }
    }
    private long available(UUID id,String account) {
        long balance=db.queryForObject("SELECT balance FROM accounts WHERE customer_id=? AND kind=?",Long.class,id,account);
        if(account.equals("savings")) balance-=db.queryForObject("SELECT COALESCE(SUM(saved),0) FROM goals WHERE customer_id=?",Long.class,id);
        return balance;
    }
    private void entry(UUID id,String account,String name,String category,long amount,UUID operation) {
        db.update("INSERT INTO ledger_entries(id,customer_id,account,name,category,amount,operation_id) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(),id,account,name,category,amount,operation);
    }
    private void seed(UUID id) {
        db.update("UPDATE accounts SET balance=CASE kind WHEN 'checking' THEN 824050 ELSE 1842000 END WHERE customer_id=?",id);
        UUID op=UUID.randomUUID();
        entry(id,"checking","Demo opening balance","Opening balance",824050,op);
        entry(id,"savings","Demo opening balance","Opening balance",1842000,op);
        db.update("INSERT INTO goals(id,customer_id,name,target,saved) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,"Japan, here I come",500000,325000);
        db.update("INSERT INTO goals(id,customer_id,name,target,saved) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,"A little peace of mind",1500000,980000);
    }
    static long cents(String amount) {
        try {
            long value=new BigDecimal(amount).movePointRight(2).longValueExact();
            require(value>0&&value<=10_000_000_000L,"Amount must be between $0.01 and $100,000,000.");
            return value;
        } catch(ArithmeticException|NullPointerException|NumberFormatException e) { throw new IllegalArgumentException("Enter a valid amount with at most two decimal places."); }
    }
    static String text(String value,int max) {
        require(value!=null&&!value.isBlank()&&value.trim().length()<=max,"Enter text between 1 and "+max+" characters.");
        return value.trim();
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    private static String hash(String type,String id,Map<String,String> fields) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var canonical=new TreeMap<>(fields);canonical.put("__type",type);canonical.put("__id",id==null?"":id);
            for(var pair:canonical.entrySet()) {
                byte[] key=pair.getKey().getBytes(StandardCharsets.UTF_8), value=Objects.toString(pair.getValue(),"").getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(key.length).array());digest.update(key);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
