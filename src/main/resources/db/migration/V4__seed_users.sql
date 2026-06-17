-- V4__seed_users.sql : GENERATED default users (usrsec.txt is absent). Passwords are BCrypt(strength=12) of 'PASSWORD'.
-- ADMIN001 = admin (user_type 'A'); USER0001 = regular user (user_type 'U'). README.md L157-158.

INSERT INTO users (user_id, first_name, last_name, password, user_type) VALUES
('ADMIN001', 'Admin', 'User', '$2b$12$/AB2EkY/fhF2TracOQz20.vKpCazRlMntQxCCt19lLLVod3Crg2y2', 'A'),
('USER0001', 'Regular', 'User', '$2b$12$0sekp.jIE.MN.OEP5BMzd.lB0hssBr6LucSyLz8XkP/o.y4Q7bXP6', 'U');
