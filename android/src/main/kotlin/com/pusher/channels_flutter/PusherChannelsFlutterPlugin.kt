package com.pusher.channels_flutter

import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.*
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import com.pusher.client.util.HttpAuthorizer
import com.pusher.client.Authorizer
import io.flutter.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.net.InetSocketAddress
import java.net.Proxy
import com.google.gson.Gson
import com.pusher.client.channel.impl.ChannelManager
import java.util.concurrent.Semaphore
import com.pusher.client.util.Factory

class PusherChannelsFlutterPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    ConnectionEventListener, ChannelEventListener, SubscriptionEventListener,
    PrivateChannelEventListener, PrivateEncryptedChannelEventListener, PresenceChannelEventListener,
    Authorizer {
    private var activity: FlutterActivity? = null
    private lateinit var methodChannel: MethodChannel
    private var pusher: Pusher? = null
    private var pusherChannels = mutableMapOf<String, Channel>()
    val TAG = "PusherChannelsFlutter"

    inner class SubChannelManager(factory: Factory?) : ChannelManager(factory) {
        private val gson = Gson()
        override fun onMessage(event: String?, wholeMessage: String?) {
            val json = gson.fromJson(wholeMessage,Map::class.java)
            onEvent(PusherEvent(json as Map<String, Any>?))
            super.onMessage(event, wholeMessage)
        }
    }

    inner class DelegateFactory : Factory() {
        @Synchronized
        override fun getChannelManager(): ChannelManager {
            val field = javaClass.superclass.getDeclaredField("channelManager")
            field.isAccessible = true
            if (field.get(this) == null) {
                field.set(this, SubChannelManager(this))
            }
            return field.get(this) as ChannelManager
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel =
            MethodChannel(
                flutterPluginBinding.binaryMessenger,
                "pusher_channels_flutter",
                GsonMethodCodec()
            )
        methodChannel.setMethodCallHandler(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity as FlutterActivity
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity as FlutterActivity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "init" -> this.init(call, result)
            "connect" -> this.connect(result)
            "disconnect" -> this.disconnect(result)
            "subscribe" -> this.subscribe(
                call.argument("channelName")!!,
                result
            )
            "unsubscribe" -> this.unsubscribe(
                call.argument("channelName")!!,
                result
            )
            "trigger" -> this.trigger(
                call.argument("channelName")!!,
                call.argument("eventName")!!,
                call.argument("data")!!,
                result
            )
            "getSocketId" -> this.getSocketId(result)
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun callback(method: String, args: Any) {
        activity!!.runOnUiThread {
            methodChannel.invokeMethod(method, args)
        }
    }

    private fun init(
        call: MethodCall,
        result: Result
    ) {
        try {
            if (pusher == null) {
                val options = PusherOptions()
                if (call.argument<String>("cluster") != null) options.setCluster(call.argument("cluster"))
                if (call.argument<Boolean>("useTLS") != null) options.isUseTLS =
                    call.argument("useTLS")!!
                if (call.argument<String>("host") != null) options.setHost(call.argument("host"))
                if (call.argument<Int>("wsPort") != null) options.setWsPort(call.argument("wsPort")!!)
                if (call.argument<Int>("wssPort") != null) options.setWssPort(call.argument("wssPort")!!)
                if (call.argument<Long>("activityTimeout") != null) options.activityTimeout =
                    call.argument("activityTimeout")!!
                if (call.argument<Long>("pongTimeout") != null) options.pongTimeout =
                    call.argument("pongTimeout")!!
                if (call.argument<Int>("maxReconnectionAttempts") != null) options.maxReconnectionAttempts =
                    call.argument("maxReconnectionAttempts")!!
                if (call.argument<Int>("maxReconnectGapInSeconds") != null) options.maxReconnectGapInSeconds =
                    call.argument("maxReconnectGapInSeconds")!!
                if (call.argument<String>("authEndpoint") != null) options.authorizer =
                    HttpAuthorizer(call.argument("authEndpoint"))
                if (call.argument<String>("authorizer") != null) options.authorizer = this
                if (call.argument<String>("proxy") != null) {
                    val (host, port) = call.argument<String>("proxy")!!.split(':')
                    options.proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port.toInt()))
                }
                val pCon = Pusher::class.java.getDeclaredConstructor(String::class.java, PusherOptions::class.java, Factory::class.java)
                pusher = pCon.newInstance(call.argument("apiKey"), options, DelegateFactory())
                // pusher = Pusher(call.argument("apiKey"), options)
            } else {
                throw Exception("Pusher Channels already initialized.")
            }
            Log.i(TAG, "Start $pusher")
            result.success(null)
        } catch (e: Exception) {
            result.error("PusherChannels", e.message, null)
        }
    }

    private fun connect(result: Result) {
        pusher!!.connect(this, ConnectionState.ALL)
        result.success(null)
    }

    private fun disconnect(result: Result) {
        pusher!!.disconnect()
        result.success(null)
    }

    private fun subscribe(channelName: String, result: Result) {
        if (pusherChannels[channelName] == null) {
            pusherChannels[channelName] = when {
                channelName.startsWith("private-") -> pusher!!.subscribePrivate(channelName, this)
                channelName.startsWith("private-encrypted-") -> pusher!!.subscribePrivateEncrypted(
                    channelName, this
                )
                channelName.startsWith("presence-") -> pusher!!.subscribePresence(
                    channelName, this
                )
                else -> pusher!!.subscribe(channelName, this)
            }
        }
        result.success(null)
    }

    private fun unsubscribe(channelName: String, result: Result) {
        pusher!!.unsubscribe(channelName)
        result.success(null)
    }

    private fun trigger(channelName: String, eventName: String, data: String, result: Result) {
        (pusherChannels[channelName] as PrivateChannel).trigger(eventName, data)
        result.success(null)
    }

    private fun getSocketId(result: Result) {
        val socketId = pusher!!.connection.socketId
        result.success(socketId)
    }

    override fun authorize(channelName: String?, socketId: String?): String? {
        var result: String? = null
        val mutex = Semaphore(0)
        activity!!.runOnUiThread {
            methodChannel.invokeMethod("onAuthorizer", mapOf(
                "channelName" to channelName,
                "socketId" to socketId
            ), object : Result {
                override fun success(o: Any?) {
                    // this will be called with o = "some string"
                    Log.i(TAG, "SUCCESS: $o")
                    if (o != null) {
                        val gson = Gson()
                        result = gson.toJson(o)
                    }
                    mutex.release()
                }

                override fun error(s: String?, s1: String?, o: Any?) {
                    Log.e(TAG, "ERROR: $s $s1 $o")
                    mutex.release()
                }

                override fun notImplemented() {
                    Log.e(TAG, "Not implemented")
                    mutex.release()
                }
            })
        }
        mutex.acquire()
        return result
    }

    // Event handlers
    override fun onConnectionStateChange(change: ConnectionStateChange) {
        callback(
            "onConnectionStateChange", mapOf(
                "previousState" to change.previousState.toString(),
                "currentState" to change.currentState.toString()
            )
        )
    }

    override fun onSubscriptionSucceeded(channelName: String) {
        // Log.i(TAG, "Subscribed to channel: $channelName")
        // Handled by global event handler
    }

    override fun onEvent(event: PusherEvent) {
        // Log.i(TAG, "Received event with data: $event")
        if (event.eventName === "pusher_internal:subscription_succeeded") {
            callback(
                "onSubscriptionSucceeded", mapOf(
                    "channelName" to event.channelName,
                    "data" to event.data
                )
            )
        } else {
            callback(
                "onEvent", mapOf(
                    "channelName" to event.channelName,
                    "eventName" to event.eventName,
                    "userId" to event.userId,
                    "data" to event.data
                )
            )
        }
    }

    override fun onAuthenticationFailure(message: String, e: Exception) {
        // Log.e(TAG, "Authentication failure due to $message, exception was $e")
        callback(
            "onSubscriptionError", mapOf(
                "message" to message,
                "error" to e.toString()
            )
        )
    } // Other ChannelEventListener methods

    override fun onUsersInformationReceived(channelName: String?, users: MutableSet<User>?) {
        // Handled by global handler.
    }

    override fun onDecryptionFailure(event: String?, reason: String?) {
        // Log.e(TAG, "Decryption failure due to $event, exception was $reason")
        callback(
            "onDecryptionFailure", mapOf(
                "event" to event,
                "reason" to reason
            )
        )
    }

    override fun userSubscribed(channelName: String, user: User) {
        // Log.i(TAG, "A new user joined channel [$channelName]: ${user.id}, ${user.info}")
        callback(
            "onMemberAdded", mapOf(
                "channelName" to channelName,
                "user" to mapOf(
                    "userId" to user.id,
                    "userInfo" to user.info
                )
            )
        )
    }

    override fun userUnsubscribed(channelName: String, user: User) {
        // Log.i(TAG, "A user left channel [$channelName]: ${user.id}, ${user.info}")
        callback(
            "onMemberRemoved", mapOf(
                "channelName" to channelName,
                "user" to mapOf(
                    "userId" to user.id,
                    "userInfo" to user.info
                )
            )
        )
    } // Other ChannelEventListener methods

    override fun onError(message: String, code: String?, e: Exception?) {
        callback(
            "onError", mapOf(
                "message" to message,
                "code" to code,
                "error" to e.toString()
            )
        )
    }

    override fun onError(message: String, e: Exception) {
        onError(message, "", e)
    }
}
