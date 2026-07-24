-- V2__seed_security_users.sql
-- Seeds security_users with the 10 legacy USRSEC users from app/jcl/DUSRSECJ.jcl
-- (VSAM KSDS AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS). Runs after V1 (table must exist).
-- sec_usr_pwd = BCrypt('PASSWORD') bare hash (never plaintext); BCrypt embeds its
-- own salt, so the single hash below is valid for all 10 rows.

INSERT INTO security_users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type) VALUES
('ADMIN001','MARGARET','GOLD',       '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','A'),
('ADMIN002','RUSSELL','RUSSELL',     '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','A'),
('ADMIN003','RAYMOND','WHITMORE',    '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','A'),
('ADMIN004','EMMANUEL','CASGRAIN',   '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','A'),
('ADMIN005','GRANVILLE','LACHAPELLE','$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','A'),
('USER0001','LAWRENCE','THOMAS',     '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','U'),
('USER0002','AJITH','KUMAR',         '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','U'),
('USER0003','LAURITZ','ALME',        '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','U'),
('USER0004','AVERARDO','MAZZI',      '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','U'),
('USER0005','LEE','TING',            '$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm','U');
