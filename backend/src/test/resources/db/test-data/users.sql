-- Test data for UserSecurityRepositoryTest
-- Passwords: 'password1', 'password2', 'adminpass'
-- Pre-hashed with BCrypt strength 10

-- Clear existing test data
DELETE FROM user_security WHERE user_id IN ('user001', 'user002', 'admin');

-- Insert test users
INSERT INTO user_security (user_id, first_name, last_name, password, user_type) 
VALUES
  ('user001', 'John', 'Smith', 
   '$2a$10$1yL3M9bMGf/rTzoD98d5VudAVjHBc/YE39gxJVf.f01q2ISx7s7y.',
   'R'),
  ('user002', 'Jane', 'Doe',
   '$2a$10$./jZ5q4Jr3r.hJqJpwvaSeRVt1yC29yyBMTty01p6jABBBBZv1r3O',
   'R'),
  ('admin', 'System', 'Administrator',
   '$2a$10$bgnsSuy/ruKvWaGBfyQaWemAfks.0hIH4mQ7pKwTWa/VKoBZ6Y.uC',
   'A');
