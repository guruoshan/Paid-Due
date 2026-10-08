package com.dailymoneyregister.feature.auth.data

/**
 * Phone-number edge-case tests for admin login have been moved to:
 * com.dailymoneyregister.core.cloud.RemoteAdminAuthRepositoryTest
 *
 * That file uses MockK (already on the test classpath) and covers:
 *  - E.164 phone rejection
 *  - Local-format phone rejection
 *  - Phone-with-spaces rejection
 *  - Blank email guard
 *  - Blank password guard
 *  - Successful email auth
 *  - No-session failure after auth call
 *  - Email normalisation (trim + lowercase)
 */
