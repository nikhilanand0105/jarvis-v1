# Multi-App Workflow Chaining & Structured Intent Routing

This plan outlines the implementation of cross-app parameter pipelining and structured intent routing using the new Android AppFunctions framework and on-device Gemma-3n.

## User Review Required

> [!IMPORTANT]
> **Experimental API**: AppFunctions is an experimental Android 16 feature. It requires `compileSdk = 37` (or similar for Android 16 previews) and `targetSdk = 36`.
> **SDK Availability**: This implementation assumes the build environment has the necessary SDKs for Android 16 (API 36/37) and the AppFunctions Jetpack libraries.
> **KSP Setup**: We will need to add the KSP (Kotlin Symbol Processing) plugin to the project to handle AppFunctions annotation processing.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle.kts](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/build.gradle.kts)
- Update `compileSdk` to `37` and `targetSdk` to `36`.
- Add KSP plugin: `alias(libs.plugins.ksp)`.
- Add AppFunctions dependencies:
    - `implementation(libs.androidx.appfunctions.runtime)`
    - `ksp(libs.androidx.appfunctions.compiler)`

#### [MODIFY] [libs.versions.toml](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/gradle/libs.versions.toml)
- Add `ksp` plugin and `appfunctions` library versions and definitions.

---

### Structured Intent Routing

#### [MODIFY] [LocalLlmEngine.kt](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/src/main/java/com/jarvispoc/ai/LocalLlmEngine.kt)
- Add a new method `parseIntent(query: String)` that uses a specialized prompt for Gemma-3n to extract parameters into a JSON-like structure.
- Parameters to extract: `target_action`, `product_query`, `price_bounds`, `recipient`, `tone`.

#### [NEW] [IntentFunctions.kt](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/src/main/java/com/jarvispoc/ai/IntentFunctions.kt)
- Create an AppFunction class for intent parsing.
- Expose `parseStructuredIntent` as an `@AppFunction`.

---

### Cross-App Parameter Pipelining (Amazon Scraping)

#### [MODIFY] [AmazonOrderFlow.kt](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/src/main/java/com/jarvispoc/flows/AmazonOrderFlow.kt)
- Implement `scrapeProductDetails(searchQuery: String)` to find the top result and extract its title, price, and rating using the existing `ActionExecutor` logic.
- Ensure this flow does *not* add to cart or proceed to checkout.

#### [NEW] [AmazonFunctions.kt](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/src/main/java/com/jarvispoc/flows/AmazonFunctions.kt)
- Create an AppFunction class for Amazon interactions.
- Expose `getProductDetails` as an `@AppFunction`.

---

### Note Drafting & Messaging

#### [NEW] [DraftingFunctions.kt](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/src/main/java/com/jarvispoc/ai/DraftingFunctions.kt)
- Expose `draftComparisonNote` as an `@AppFunction`.
- Uses `LocalLlmEngine` to compare multiple products or summarize a single one.

#### [NEW] [MessagingFunctions.kt](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/src/main/java/com/jarvispoc/flows/MessagingFunctions.kt)
- Expose `openMessagingComposer` as an `@AppFunction`.
- Uses an `ACTION_SEND` Intent to pass the drafted note to another app.

---

### System Integration

#### [NEW] [JarvisAppFunctionService.kt](file:///Users/kartikbehl/Desktop/peaceful-carson/jarvis-v1/app/src/main/java/com/jarvispoc/service/JarvisAppFunctionService.kt)
- Implement `AppFunctionService` (or use the Jetpack library's auto-generated one) to register these functions with the system.

## Verification Plan

### Automated Tests
- Unit tests for `LocalLlmEngine.parseIntent` to verify parameter extraction.
- Unit tests for `AmazonOrderFlow.scrapeProductDetails` logic (mocking `ActionExecutor`).

### Manual Verification
- Deploy to a device running Android 16 (if available) or use ADB commands to list and invoke AppFunctions.
- Verify that "Scrape Amazon" returns correct data.
- Verify that "Draft Note" uses the scraped data correctly.
- Verify that "Open Messaging" launches the share sheet with the correct text.
