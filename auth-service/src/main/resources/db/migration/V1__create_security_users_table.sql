-- security_users: application security-user store for the auth-service.
-- Re-platforms legacy VSAM KSDS USRSEC (AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)
-- consumed by COBOL sign-on COSGN00C (CICS transaction CC00).
-- Source contracts: app/cpy/CSUSR01Y.cpy (80-byte SEC-USER-DATA layout),
-- app/jcl/DUSRSECJ.jcl (KEYS(8,0), RECORDSIZE(80,80)), app/csd/CARDDEMO.CSD.
-- Column names/types are the authoritative match for the JPA entity
-- com.carddemo.common.domain.SecurityUser (@Table "security_users").

CREATE TABLE security_users (
    sec_usr_id    VARCHAR(8)   PRIMARY KEY,   -- SEC-USR-ID    PIC X(08); VSAM KEYS(8,0) 8-byte key at offset 0
    sec_usr_fname VARCHAR(20)  NOT NULL,      -- SEC-USR-FNAME PIC X(20)
    sec_usr_lname VARCHAR(20)  NOT NULL,      -- SEC-USR-LNAME PIC X(20)
    sec_usr_pwd   VARCHAR(100) NOT NULL,      -- SEC-USR-PWD   PIC X(08) legacy; WIDENED to 100 for BCrypt hash (never plaintext)
    sec_usr_type  VARCHAR(1)   NOT NULL,      -- SEC-USR-TYPE  PIC X(01); 'A' or 'U'
    CONSTRAINT chk_sec_usr_type CHECK (sec_usr_type IN ('A','U'))
);
