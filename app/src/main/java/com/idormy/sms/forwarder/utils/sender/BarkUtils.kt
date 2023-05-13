package com.idormy.sms.forwarder.utils.sender

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.idormy.sms.forwarder.database.entity.Rule
import com.idormy.sms.forwarder.entity.MsgInfo
import com.idormy.sms.forwarder.entity.result.BarkResult
import com.idormy.sms.forwarder.entity.setting.BarkSetting
import com.idormy.sms.forwarder.utils.SendUtils
import com.idormy.sms.forwarder.utils.SettingUtils
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.cache.model.CacheMode
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Suppress("unused")
class BarkUtils {
    companion object {

        private val TAG: String = BarkUtils::class.java.simpleName

        fun sendMsg(
            setting: BarkSetting,
            msgInfo: MsgInfo,
            rule: Rule? = null,
            senderIndex: Int = 0,
            logId: Long = 0L,
            msgId: Long = 0L
        ) {
            val title: String = if (rule != null) {
                msgInfo.getTitleForSend(setting.title.toString(), rule.regexReplace)
            } else {
                msgInfo.getTitleForSend(setting.title.toString())
            }
            val content: String = if (rule != null) {
                msgInfo.getContentForSend(rule.smsTemplate, rule.regexReplace)
            } else {
                msgInfo.getContentForSend(SettingUtils.smsTemplate)
            }

            val requestUrl: String = setting.server //推送地址
            Log.i(TAG, "requestUrl:$requestUrl")

            //支持HTTP基本认证(Basic Authentication)
            val regex = "^(https?://)([^:]+):([^@]+)@(.+)"
            val matches = Regex(regex, RegexOption.IGNORE_CASE).findAll(requestUrl).toList().flatMap(MatchResult::groupValues)
            Log.i(TAG, "matches = $matches")
            val request = if (matches.isNotEmpty()) {
                XHttp.post(matches[1] + matches[4]).addInterceptor(BasicAuthInterceptor(matches[2], matches[3]))
            } else {
                XHttp.post(requestUrl)
            }

            val msgMap: MutableMap<String, Any> = mutableMapOf()
            msgMap["title"] = title
            msgMap["body"] = content
            msgMap["isArchive"] = 1
            if (!TextUtils.isEmpty(setting.group)) msgMap["group"] = setting.group.toString()
            if (!TextUtils.isEmpty(setting.icon)) msgMap["icon"] = setting.icon.toString()
            if (!TextUtils.isEmpty(setting.level)) msgMap["level"] = setting.level.toString()
            if (!TextUtils.isEmpty(setting.sound)) msgMap["sound"] = setting.sound.toString()
            if (!TextUtils.isEmpty(setting.badge)) msgMap["badge"] = setting.badge.toString()
            if (!TextUtils.isEmpty(setting.url)) msgMap["url"] = setting.url.toString()

            //自动复制验证码
            val pattern = Regex("(?<!回复)(验证码|授权码|校验码|检验码|确认码|激活码|动态码|安全码|(验证)?代码|校验代码|检验代码|激活代码|确认代码|动态代码|安全代码|登入码|认证码|识别码|短信口令|动态密码|交易码|上网密码|动态口令|随机码|驗證碼|授權碼|校驗碼|檢驗碼|確認碼|激活碼|動態碼|(驗證)?代碼|校驗代碼|檢驗代碼|確認代碼|激活代碼|動態代碼|登入碼|認證碼|識別碼|一次性密码|[Cc][Oo][Dd][Ee]|[Vv]erification)")
            if (pattern.containsMatchIn(content)) {
                var code = content.replace("(.*)((代|授权|验证|动态|校验)码|[【\\[].*[】\\]]|[Cc][Oo][Dd][Ee]|[Vv]erification\\s?([Cc]ode)?)\\s?(G-|<#>)?([:：\\s是为]|[Ii][Ss]){0,3}[\\(（\\[【{「]?(([0-9\\s]{4,7})|([\\dA-Za-z]{5,6})(?!([Vv]erification)?([Cc][Oo][Dd][Ee])|:))[」}】\\]）\\)]?(?=([^0-9a-zA-Z]|\$))(.*)".toRegex(), "$7").trim()
                code = code.replace("[^\\d]*[\\(（\\[【{「]?([0-9]{3}\\s?[0-9]{1,3})[」}】\\]）\\)]?(?=.*((代|授权|验证|动态|校验)码|[【\\[].*[】\\]]|[Cc][Oo][Dd][Ee]|[Vv]erification\\s?([Cc]ode)?))(.*)".toRegex(), "$1").trim()
                if (code.isNotEmpty()) {
                    msgMap["copy"] = code
                    msgMap["automaticallyCopy"] = 1
                }
            }

            val requestMsg: String = Gson().toJson(msgMap)
            Log.i(TAG, "requestMsg:$requestMsg")
            //推送加密
            if (setting.transformation.isNotEmpty() && "none" != setting.transformation && setting.key.isNotEmpty() && setting.iv.isNotEmpty()) {
                var transformation = setting.transformation.replace("AES128", "AES").replace("AES192", "AES").replace("AES256", "AES")
                val ciphertext = encrypt(requestMsg, transformation, setting.key, setting.iv)
                request.params("ciphertext", URLEncoder.encode(ciphertext, "UTF-8"))
                request.params("iv", URLEncoder.encode(setting.iv, "UTF-8"))
            } else {
                request.upJson(requestMsg)
            }

            request.ignoreHttpsCert() //忽略https证书
                .keepJson(true)
                .timeOut((SettingUtils.requestTimeout * 1000).toLong()) //超时时间10s
                .cacheMode(CacheMode.NO_CACHE)
                .retryCount(SettingUtils.requestRetryTimes) //超时重试的次数
                .retryDelay(SettingUtils.requestDelayTime) //超时重试的延迟时间
                .retryIncreaseDelay(SettingUtils.requestDelayTime) //超时重试叠加延时
                .timeStamp(true)
                .execute(object : SimpleCallBack<String>() {

                    override fun onError(e: ApiException) {
                        Log.e(TAG, e.detailMessage)
                        val status = 0
                        SendUtils.updateLogs(logId, status, e.displayMessage)
                        SendUtils.senderLogic(status, msgInfo, rule, senderIndex, msgId)
                    }

                    override fun onSuccess(response: String) {
                        Log.i(TAG, response)

                        val resp = Gson().fromJson(response, BarkResult::class.java)
                        val status = if (resp?.code == 200L) 2 else 0
                        SendUtils.updateLogs(logId, status, response)
                        SendUtils.senderLogic(status, msgInfo, rule, senderIndex, msgId)

                    }

                })

        }

        fun encrypt(plainText: String, transformation: String, key: String, iv: String): String {
            val cipher = Cipher.getInstance(transformation)
            val keySpec = SecretKeySpec(key.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(iv.toByteArray())
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return Base64.encode(encryptedBytes, Base64.NO_WRAP).toString()
        }

    }
}