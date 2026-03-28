/*
 * JNI bridge between dev.sieve.address.LibPostal and the libpostal C library.
 *
 * Compile with:
 *   cc -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/<os> \
 *      -o libsieve_postal.so dev_sieve_address_LibPostal.c -lpostal
 *
 * See the accompanying Makefile for platform-aware build instructions.
 */

#include <jni.h>
#include <libpostal/libpostal.h>
#include <stdlib.h>
#include <string.h>

/* ---- Setup / teardown -------------------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_dev_sieve_address_LibPostal_nativeSetup(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jboolean)libpostal_setup();
}

JNIEXPORT jboolean JNICALL
Java_dev_sieve_address_LibPostal_nativeSetupDataDir(JNIEnv *env, jclass cls,
                                                     jstring jDataDir)
{
    (void)cls;
    const char *dataDir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
    if (dataDir == NULL) return JNI_FALSE;
    jboolean result = (jboolean)libpostal_setup_datadir((char *)dataDir);
    (*env)->ReleaseStringUTFChars(env, jDataDir, dataDir);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_sieve_address_LibPostal_nativeSetupParser(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jboolean)libpostal_setup_parser();
}

JNIEXPORT jboolean JNICALL
Java_dev_sieve_address_LibPostal_nativeSetupParserDataDir(JNIEnv *env, jclass cls,
                                                           jstring jDataDir)
{
    (void)cls;
    const char *dataDir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
    if (dataDir == NULL) return JNI_FALSE;
    jboolean result = (jboolean)libpostal_setup_parser_datadir((char *)dataDir);
    (*env)->ReleaseStringUTFChars(env, jDataDir, dataDir);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_sieve_address_LibPostal_nativeSetupLanguageClassifier(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jboolean)libpostal_setup_language_classifier();
}

JNIEXPORT jboolean JNICALL
Java_dev_sieve_address_LibPostal_nativeSetupLanguageClassifierDataDir(JNIEnv *env, jclass cls,
                                                                       jstring jDataDir)
{
    (void)cls;
    const char *dataDir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
    if (dataDir == NULL) return JNI_FALSE;
    jboolean result = (jboolean)libpostal_setup_language_classifier_datadir((char *)dataDir);
    (*env)->ReleaseStringUTFChars(env, jDataDir, dataDir);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_sieve_address_LibPostal_nativeTeardown(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    libpostal_teardown();
}

JNIEXPORT void JNICALL
Java_dev_sieve_address_LibPostal_nativeTeardownParser(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    libpostal_teardown_parser();
}

JNIEXPORT void JNICALL
Java_dev_sieve_address_LibPostal_nativeTeardownLanguageClassifier(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    libpostal_teardown_language_classifier();
}

/* ---- Address expansion ------------------------------------------------- */

JNIEXPORT jobjectArray JNICALL
Java_dev_sieve_address_LibPostal_nativeExpandAddress(JNIEnv *env, jclass cls,
                                                      jstring jAddress)
{
    (void)cls;
    const char *address = (*env)->GetStringUTFChars(env, jAddress, NULL);
    if (address == NULL) return NULL;

    libpostal_normalize_options_t options = libpostal_get_default_options();
    size_t n = 0;
    char **expansions = libpostal_expand_address((char *)address, options, &n);

    (*env)->ReleaseStringUTFChars(env, jAddress, address);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)n, stringClass, NULL);

    for (size_t i = 0; i < n; i++) {
        jstring str = (*env)->NewStringUTF(env, expansions[i]);
        (*env)->SetObjectArrayElement(env, result, (jsize)i, str);
        (*env)->DeleteLocalRef(env, str);
    }

    libpostal_expansion_array_destroy(expansions, n);
    return result;
}

/* ---- Address parsing --------------------------------------------------- */

/*
 * Returns String[n][2] where each inner array is [label, value].
 * Converted to AddressComponent objects on the Java side.
 */
JNIEXPORT jobjectArray JNICALL
Java_dev_sieve_address_LibPostal_nativeParseAddress(JNIEnv *env, jclass cls,
                                                     jstring jAddress)
{
    (void)cls;
    const char *address = (*env)->GetStringUTFChars(env, jAddress, NULL);
    if (address == NULL) return NULL;

    libpostal_address_parser_options_t options =
        libpostal_get_address_parser_default_options();
    libpostal_address_parser_response_t *response =
        libpostal_parse_address((char *)address, options);

    (*env)->ReleaseStringUTFChars(env, jAddress, address);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jclass stringArrayClass = (*env)->FindClass(env, "[Ljava/lang/String;");

    if (response == NULL) {
        return (*env)->NewObjectArray(env, 0, stringArrayClass, NULL);
    }

    jobjectArray result = (*env)->NewObjectArray(
        env, (jsize)response->num_components, stringArrayClass, NULL);

    for (size_t i = 0; i < response->num_components; i++) {
        jobjectArray pair = (*env)->NewObjectArray(env, 2, stringClass, NULL);

        jstring label = (*env)->NewStringUTF(env, response->labels[i]);
        jstring value = (*env)->NewStringUTF(env, response->components[i]);

        (*env)->SetObjectArrayElement(env, pair, 0, label);
        (*env)->SetObjectArrayElement(env, pair, 1, value);
        (*env)->SetObjectArrayElement(env, result, (jsize)i, pair);

        (*env)->DeleteLocalRef(env, label);
        (*env)->DeleteLocalRef(env, value);
        (*env)->DeleteLocalRef(env, pair);
    }

    libpostal_address_parser_response_destroy(response);
    return result;
}
