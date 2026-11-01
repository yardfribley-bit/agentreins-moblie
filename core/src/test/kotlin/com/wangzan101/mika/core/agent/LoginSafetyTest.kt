package com.wangzan101.mika.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSafetyTest {
    @Test fun passwordAndOtpRejectionsCountOnlyOnSubmit() {
        assertTrue(LoginSafety.rejected("密码有误。请重试"))
        assertTrue(LoginSafety.rejected("Wrong password. Try again"))
        assertTrue(LoginSafety.rejected("验证码不正确"))
        assertFalse(LoginSafety.rejected("请输入密码"))
        assertFalse(LoginSafety.rejected("登录方式是密码，已绑定两步验证"))
        assertTrue(LoginSafety.couldSubmit("phone_tap"))
        assertTrue(LoginSafety.couldSubmit("do_steps"))
        assertFalse(LoginSafety.couldSubmit("phone_type_text"))
        assertFalse(LoginSafety.couldSubmit("phone_observe"))
    }

    @Test fun biometricHandsOffButCaptchaDoesNot() {
        assertTrue(LoginSafety.biometric("请验证指纹"))
        assertTrue(LoginSafety.biometric("摄像头真人验证"))
        assertTrue(LoginSafety.biometric("face verification"))
        assertFalse(LoginSafety.biometric("进行人机身份验证"))
        assertFalse(LoginSafety.biometric("选择含小轿车的图片"))
    }
}
