#include <jni.h>
#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_utf_chars.h>

#include "android-base/logging.h"
#include "leveldb/db.h"

#ifndef _Included_com_android_providers_media_leveldb_LevelDBInstance
#define _Included_com_android_providers_media_leveldb_LevelDBInstance
#ifdef __cplusplus
extern "C" {
#endif
#undef com_android_providers_media_leveldb_LevelDBInstance_MAX_BULK_INSERT_ENTRIES
#define com_android_providers_media_leveldb_LevelDBInstance_MAX_BULK_INSERT_ENTRIES 100L

static std::string getStatusCode(leveldb::Status status) {
    if (status.ok()) {
        return "0";
    } else if (status.IsNotFound()) {
        return "1";
    } else if (status.IsInvalidArgument()) {
        return "2";
    } else {
        return "3";
    }
}

static jobject createLevelDBResult(JNIEnv* env, leveldb::Status status, std::string value) {
    ScopedLocalRef<jclass> levelDbResultClass(
            env, env->FindClass("com/android/providers/media/leveldb/LevelDBResult"));
    if (!levelDbResultClass.get()) {
        return nullptr;
    }

    ScopedLocalRef<jobject> levelDbResultData(env, env->AllocObject(levelDbResultClass.get()));
    if (!levelDbResultData.get()) {
        return nullptr;
    }

    jfieldID codeField = env->GetFieldID(levelDbResultClass.get(), "mCode", "Ljava/lang/String;");
    jfieldID messageField =
            env->GetFieldID(levelDbResultClass.get(), "mErrorMessage", "Ljava/lang/String;");
    jfieldID valueField = env->GetFieldID(levelDbResultClass.get(), "mValue", "Ljava/lang/String;");

    std::string statusCode = getStatusCode(status);
    ScopedLocalRef<jstring> j_code(env, env->NewStringUTF(statusCode.c_str()));
    ScopedLocalRef<jstring> j_message(env, env->NewStringUTF(status.ToString().c_str()));
    ScopedLocalRef<jstring> j_value(env, env->NewStringUTF(value.c_str()));

    if (j_code.get()) {
        env->SetObjectField(levelDbResultData.get(), codeField, j_code.get());
    }
    if (j_message.get()) {
        env->SetObjectField(levelDbResultData.get(), messageField, j_message.get());
    }
    if (j_value.get()) {
        env->SetObjectField(levelDbResultData.get(), valueField, j_value.get());
    }

    return levelDbResultData.release();
}

static leveldb::Status insertInLevelDB(JNIEnv* env, jobject obj, jlong leveldbptr,
                                       jobject leveldbentry) {
    ScopedLocalRef<jclass> levelDbEntryClass(env, env->GetObjectClass(leveldbentry));
    if (!levelDbEntryClass.get()) {
        return leveldb::Status::NotSupported("Class not found");
    }

    jmethodID getKeyMethodId =
            env->GetMethodID(levelDbEntryClass.get(), "getKey", "()Ljava/lang/String;");
    jmethodID getValueMethodId =
            env->GetMethodID(levelDbEntryClass.get(), "getValue", "()Ljava/lang/String;");

    ScopedLocalRef<jstring> key(env, (jstring)env->CallObjectMethod(leveldbentry, getKeyMethodId));
    ScopedLocalRef<jstring> value(env,
                                  (jstring)env->CallObjectMethod(leveldbentry, getValueMethodId));

    ScopedUtfChars utf_chars_key(env, key.get());
    ScopedUtfChars utf_chars_value(env, value.get());
    leveldb::DB* leveldb = reinterpret_cast<leveldb::DB*>(leveldbptr);
    leveldb::Status status;
    status = leveldb->Put(leveldb::WriteOptions(), utf_chars_key.c_str(), utf_chars_value.c_str());
    return status;
}

static jobject insert(JNIEnv* env, jobject obj, jlong leveldbptr, jobject leveldbentry) {
    return createLevelDBResult(env, insertInLevelDB(env, obj, leveldbptr, leveldbentry), "");
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeCreateInstance
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL
            Java_com_android_providers_media_leveldb_LevelDBInstance_nativeCreateInstance(
                JNIEnv* env, jclass leveldbInstanceClass, jstring path) {
    ScopedUtfChars utf_chars_path(env, path);
    leveldb::Options options;
    options.create_if_missing = true;
    leveldb::DB* leveldb;
    leveldb::Status status = leveldb::DB::Open(options, utf_chars_path.c_str(), &leveldb);
    if (status.ok()) {
        return reinterpret_cast<jlong>(leveldb);
    } else {
        LOG(INFO) << "Leveldb connection failed for path: " << path
                  << " with error:" << status.ToString();

        long val = 0;
        return (jlong)val;
    }
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeQuery
 * Signature: (JLjava/lang/String;)Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeQuery(
        JNIEnv* env, jobject obj, jlong leveldbptr, jstring path) {
    ScopedUtfChars utf_chars_path(env, path);
    leveldb::DB* leveldb = reinterpret_cast<leveldb::DB*>(leveldbptr);
    leveldb::Status status;
    std::string value = "";
    status = leveldb->Get(leveldb::ReadOptions(), utf_chars_path.c_str(), &value);
    return createLevelDBResult(env, status, value);
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeInsert
 * Signature: (JLcom/android/providers/media/leveldb/LevelDBEntry;)
 *                   Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeInsert(
        JNIEnv* env, jobject obj, jlong leveldbptr, jobject leveldbentry) {
    return insert(env, obj, leveldbptr, leveldbentry);
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeBulkInsert
 * Signature: (JLjava/util/List;)Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeBulkInsert(
        JNIEnv* env, jobject obj, jlong leveldbptr, jobject entries) {
    ScopedLocalRef<jclass> listClass(env, env->GetObjectClass(entries));
    if (!listClass.get()) {
        return createLevelDBResult(env, leveldb::Status::NotSupported("Class not found"), "");
    }

    jmethodID iteratorMethod =
            env->GetMethodID(listClass.get(), "iterator", "()Ljava/util/Iterator;");
    if (!iteratorMethod) {
        return createLevelDBResult(env, leveldb::Status::NotSupported("Method ID not found"), "");
    }

    ScopedLocalRef<jobject> iterator(env, env->CallObjectMethod(entries, iteratorMethod));
    if (!iterator.get()) {
        return createLevelDBResult(env, leveldb::Status::NotSupported("Iterator not found"), "");
    }

    ScopedLocalRef<jclass> iteratorClass(env, env->GetObjectClass(iterator.get()));
    if (!iteratorClass.get()) {
        return createLevelDBResult(env, leveldb::Status::NotSupported("Iterator class not found"),
                                   "");
    }

    jmethodID hasNextMethod = env->GetMethodID(iteratorClass.get(), "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass.get(), "next", "()Ljava/lang/Object;");
    if (!hasNextMethod || !nextMethod) {
        return createLevelDBResult(env, leveldb::Status::NotSupported("Iterator methods not found"),
                                   "");
    }

    leveldb::Status status;

    while (env->CallBooleanMethod(iterator.get(), hasNextMethod)) {
        ScopedLocalRef<jobject> jLevelDBEntryObject(
                env, env->CallObjectMethod(iterator.get(), nextMethod));
        if (!jLevelDBEntryObject.get()) {
            status = leveldb::Status::NotSupported("Next item not found");
            break;
        }

        status = insertInLevelDB(env, obj, leveldbptr, jLevelDBEntryObject.get());
        if (!status.ok()) {
            break;
        }
    }

    return createLevelDBResult(env, status, "");
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeDelete
 * Signature: (JLjava/lang/String;)Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeDelete(
        JNIEnv* env, jobject obj, jlong leveldbptr, jstring key) {
    ScopedUtfChars utf_chars_key(env, key);
    leveldb::DB* leveldb = reinterpret_cast<leveldb::DB*>(leveldbptr);
    leveldb::Status status;
    status = leveldb->Delete(leveldb::WriteOptions(), utf_chars_key.c_str());
    return createLevelDBResult(env, status, "");
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeDeleteLevelDb
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeDeleteLevelDb(
        JNIEnv* env, jobject obj, jlong leveldbptr) {
    leveldb::DB* leveldb = reinterpret_cast<leveldb::DB*>(leveldbptr);
    delete leveldb;
}

#ifdef __cplusplus
}
#endif
#endif
