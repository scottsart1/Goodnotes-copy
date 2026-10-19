package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.engine.SherpaVoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * sherpa-onnx's JNI finds the diarization progress callback with
 * GetMethodID(cls, "invoke", "(IIJ)Ljava/lang/Integer;"). If the method is missing, the native
 * pass continues with a Java exception pending and its next JNI calls abort the process (the
 * app "dies" during speaker identification). Loading the callback class does not load the
 * native library, so this runs on the JVM.
 */
class SherpaCallbackSignatureTest {
    private fun exactInvoke(cls: Class<*>) =
        cls.methods.firstOrNull {
            it.name == "invoke" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)) &&
                it.returnType == Integer::class.java
        }

    @Test
    fun theCallbackHasTheSignatureTheNativeCodeLooksUp() {
        val m = exactInvoke(SherpaVoiceEngine.ProgressCallback::class.java)
        assertNotNull("invoke(IIJ)Ljava/lang/Integer; is required by libsherpa-onnx-jni", m)
        var reported = -1f
        val cb = SherpaVoiceEngine.ProgressCallback { reported = it }
        assertEquals(0, m!!.invoke(cb, 3, 4, 0L))
        assertEquals(0.75f, reported, 1e-6f)
        // No division by zero when sherpa reports no chunks.
        assertEquals(0, cb(0, 0, 0L))
    }

    @Test
    fun aPlainKotlinLambdaWouldNotHaveIt() {
        // Why the named class exists: since Kotlin 2.0 lambdas compile through invokedynamic and
        // only implement the erased invoke(Object, Object, Object).
        val lambda: (Int, Int, Long) -> Int = { _, _, _ -> 0 }
        assertNull(exactInvoke(lambda.javaClass))
    }
}
