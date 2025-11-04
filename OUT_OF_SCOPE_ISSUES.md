# Out-of-Scope Issues Discovered During Validation

## Issue 1: Integration Test Failures Due to Bean Definition Conflict [RESOLVED]

### Scope Classification
**INFRASTRUCTURE ISSUE** - Fixed by setup/infrastructure agent

### Issue Description
Integration tests failed with `BeanDefinitionOverrideException` when loading the full Spring application context.

**Error Message:**
```
org.springframework.beans.factory.support.BeanDefinitionOverrideException: 
Invalid bean definition with name 'jobRepository' defined in class path resource 
[org/springframework/boot/autoconfigure/batch/BatchAutoConfiguration$SpringBootBatchConfiguration.class]: 
Cannot register bean definition for bean 'jobRepository' since there is already 
[Root bean defined in class path resource [com/carddemo/config/BatchConfig.class]] bound.
```

### Root Cause
`BatchConfig.java` manually defines a `jobRepository` bean (line 153), but Spring Boot 3.x auto-configuration 
(`BatchAutoConfiguration`) also attempts to create a `jobRepository` bean. This creates a bean definition conflict.

In Spring Boot 3.x with Spring Batch 5.x, the framework provides `JobRepository` automatically unless custom 
configuration is required.

### Impact
- **Unit Tests:** ✅ All 421 unit tests PASS (unit tests don't load full application context)
- **Integration Tests:** ❌ All 44 integration tests FAIL (integration tests load full application context)
- **Compilation:** ✅ Project compiles successfully
- **In-Scope Files:** ✅ All in-scope files work correctly

### Affected Integration Tests
- AuthenticationIntegrationTest (15 tests)
- UserManagementIntegrationTest (29 tests)

### Recommended Fix (for out-of-scope validator)
Remove or modify the `jobRepository` bean definition in `BatchConfig.java`:

**Option 1:** Remove manual bean definition and rely on Spring Boot auto-configuration
```java
// Remove @Bean method at line 153-178 in BatchConfig.java
// Spring Boot will auto-configure JobRepository
```

**Option 2:** Add `@Primary` annotation to resolve conflict
```java
@Bean
@Primary  // Add this annotation
public JobRepository jobRepository(DataSource dataSource, 
                                  PlatformTransactionManager transactionManager) throws Exception {
    // ... existing implementation
}
```

**Option 3:** Disable Spring Batch auto-configuration
```yaml
# In application.yml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration
```

### Resolution Applied
**Fixed by:** Setup/Infrastructure Agent (November 4, 2025)
**Commit:** cae1d2e

**Changes Made:**
1. Removed manual `jobRepository` bean from BatchConfig.java
2. Updated application.yml with Spring Batch 3.x properties:
   - `spring.batch.jdbc.isolation-level-for-create: SERIALIZABLE`
   - `spring.batch.jdbc.max-varchar-length: 2500`
3. Relies on Spring Boot auto-configuration for JobRepository

**Validation Results After Fix:**
- ✅ All 421 unit tests pass
- ✅ Integration test context loading succeeds (was failing before)
- ✅ 36/44 integration tests pass (UserManagementIntegrationTest: 19/19, MenuNavigationIntegrationTest: 10/10, batch tests)
- ❌ 8/44 integration tests fail (AuthenticationIntegrationTest - see Issue 2 below)

---

## Issue 2: AuthenticationIntegrationTest Failures Due to StackOverflowError [ACTIVE]

### Scope Classification
**SOURCE CODE ISSUE** - Out of scope for setup/infrastructure agent

### Issue Description
8 authentication integration tests fail with HTTP 500 INTERNAL_SERVER_ERROR due to a StackOverflowError 
in the authentication service code.

**Error Message:**
```
ERROR c.c.exception.GlobalExceptionHandler - Unhandled exception: Handler dispatch failed: java.lang.StackOverflowError
jakarta.servlet.ServletException: Handler dispatch failed: java.lang.StackOverflowError
```

### Root Cause
Infinite recursion in authentication service code (likely circular method calls or bean dependencies).

### Impact
- **Unit Tests:** ✅ All 421 unit tests PASS (including AuthenticationServiceTest)
- **Integration Tests:** ❌ 8/15 AuthenticationIntegrationTest tests FAIL with 500 errors
- **Other Integration Tests:** ✅ 36 tests PASS (UserManagementIntegrationTest, MenuNavigationIntegrationTest, etc.)
- **Compilation:** ✅ Project compiles successfully

### Affected Integration Tests
All failures in AuthenticationIntegrationTest:
1. testSuccessfulLogin (line 400)
2. testSuccessfulLoginAdminUser (line 444)
3. testLoginWithLowercaseUserId (line 694)
4. testJwtTokenFormatValidation (line 728)
5. testLogoutWithValidToken (line 771)
6. testAuthenticationPerformanceRequirement (line 851)
7. testLoginResponseStructureMatchesCobolScreen (line 891)
8. testUserSecurityEntityRetrievalDuringAuth (line 938)

### Recommended Fix (for source code agent)
Investigate authentication service for circular dependencies or infinite recursion:
- Check AuthenticationService.java for method calls that might loop
- Check AuthenticationController.java for circular dependencies
- Check JwtTokenProvider.java for recursive token validation
- Review CustomUserDetailsService.java for circular user loading

### Notes
This is a **source code bug**, not an infrastructure/configuration issue. The setup environment is 
fully operational. Unit tests pass, which suggests the issue is specific to the full Spring Boot 
application context in integration tests (possibly circular bean dependencies or request handling).

## Test Failures in Out-of-Scope Files (Discovered During Validation)

### TransactionDataLoadJobTest - Multiple Test Failures

**File**: `backend/src/test/java/com/carddemo/batch/TransactionDataLoadJobTest.java`

**Status**: Out of Scope (not assigned file)

**Issues**:

1. **testTransactionDataLoadJob_ChunkProcessing** - Assertion failure
   - Expected: >= 2500L
   - Actual: 1500L
   - Appears to be a test data or logic issue in the test itself

2. **testTransactionDataLoadJob_CheckpointRestart** - JobInstanceAlreadyComplete exception
   - Error: "A job instance already exists and is complete"
   - Indicates test is not properly cleaning up between runs or using non-unique job parameters

3. **testTransactionDataLoadJob_DataIntegrity** - LazyInitialization exception
   - Error: "could not initialize proxy [com.carddemo.entity.Card#4532123456789000] - no Session"
   - Indicates missing @Transactional annotation or session management issue

4. **testTransactionDataLoadJob_ForeignKeyValidation** - DataIntegrityViolation
   - Error: Foreign key constraint violation for card_number '9999999999999999'
   - Test is trying to create transactions with non-existent card reference

**Impact**: These test failures are pre-existing and unrelated to StatementFormattingJobTest validation work.

**Recommendation**: TransactionDataLoadJobTest needs comprehensive review and fixes for proper test isolation, data setup, and transaction management.

