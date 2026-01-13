package com.abedalkareem.games_services

import com.abedalkareem.games_services.models.Method
import com.abedalkareem.games_services.models.PendingOperation
import com.abedalkareem.games_services.models.PlayerData
import com.abedalkareem.games_services.models.value
import com.abedalkareem.games_services.util.AppImageLoader
import com.abedalkareem.games_services.util.Messages
import com.abedalkareem.games_services.util.PluginError
import com.abedalkareem.games_services.util.errorCode
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.games.AuthenticationResult
import com.google.android.gms.games.gamessignin.AuthScope
import com.google.android.gms.games.GamesSignInClient
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.PlayersClient
import com.google.android.gms.tasks.Task
import com.google.gson.Gson
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Auth(private var activityPluginBinding: ActivityPluginBinding) :
  EventChannel.StreamHandler {

  //region Variables
  private val gamesSignInClient: GamesSignInClient
    get() {
      return PlayGames.getGamesSignInClient(activityPluginBinding.activity)
    }

  private val playersClient: PlayersClient
    get() {
      return PlayGames.getPlayersClient(activityPluginBinding.activity)
    }

  private val gson = Gson()
  private var pendingOperation: PendingOperation? = null
  private var eventSink: EventChannel.EventSink? = null
  //endregion

  //region Public Methods
  fun signIn(result: MethodChannel.Result) {
    val apiAvailability = GoogleApiAvailability.getInstance()
    val resultCode = apiAvailability.isGooglePlayServicesAvailable(activityPluginBinding.activity)
    if (resultCode != ConnectionResult.SUCCESS) {
      if (apiAvailability.isUserResolvableError(resultCode)) {
        apiAvailability.getErrorDialog(activityPluginBinding.activity, resultCode, 1)?.show()
      }
      result.error(
        PluginError.FailedToAuthenticate.errorCode(),
        apiAvailability.getErrorString(resultCode),
        null
      )
      return
    }
    pendingOperation =
      PendingOperation(Method.SignIn.value(), result, activityPluginBinding.activity)
    gamesSignInClient.signIn().addOnSuccessListener {
      it?.let { result ->
        if (result.isAuthenticated) {
          playersClient.currentPlayer.addOnSuccessListener { player ->
            var playerData = PlayerData(
              player.displayName,
              player.playerId,
              null
            )
            val handler = CoroutineExceptionHandler { _, _ ->
              eventSink?.success(gson.toJson(playerData) ?: "")
              finishPendingOperationWithSuccess()
            }
            CoroutineScope(Dispatchers.Main + handler).launch {
              val image = player.iconImageUri?.let { value ->
                AppImageLoader().loadImageFromUri(activityPluginBinding.activity, value)
              }
              playerData = playerData.copy(iconImage = image)
              eventSink?.success(gson.toJson(playerData) ?: "")
              finishPendingOperationWithSuccess()
            }
          }.addOnFailureListener { error ->
            finishPendingOperationWithError(
              PluginError.FailedToAuthenticate.errorCode(),
              error.message ?: ""
            )
          }
        } else {
          finishPendingOperationWithError(PluginError.FailedToAuthenticate.errorCode(), "")
        }
      }
    }.addOnFailureListener {
      finishPendingOperationWithError(
        PluginError.FailedToAuthenticate.errorCode(),
        it.message ?: ""
      )
    }
  }

  fun getAuthCode(
    clientID: String,
    forceRefreshToken: Boolean,
    additionalScopes: List<String>,
    result: MethodChannel.Result
  ) {
    val scopes = mapAdditionalScopes(additionalScopes)
    if (scopes.isEmpty()) {
      gamesSignInClient.requestServerSideAccess(clientID, forceRefreshToken).addOnSuccessListener {
        result.success(it)
      }.addOnFailureListener {
        result.error(PluginError.FailedToGetAuthCode.errorCode(), it.message ?: "", null)
      }
    } else {
      gamesSignInClient.requestServerSideAccess(clientID, forceRefreshToken, scopes)
        .addOnSuccessListener { response ->
          result.success(response.authCode)
        }.addOnFailureListener {
          result.error(PluginError.FailedToGetAuthCode.errorCode(), it.message ?: "", null)
        }
    }
  }

  fun getPlayerProfileImage(result: MethodChannel.Result) {
    playersClient.currentPlayer.addOnSuccessListener { player ->
      val handler = CoroutineExceptionHandler { _, exception ->
        result.error(
          PluginError.FailedToGetPlayerProfileImage.errorCode(),
          exception.localizedMessage,
          null
        )
      }
      CoroutineScope(Dispatchers.Main + handler).launch {
        val image = player.hiResImageUri
          ?.let { AppImageLoader().loadImageFromUri(activityPluginBinding.activity, it) }
        result.success(image)
      }
    }.addOnFailureListener {
      result.error(PluginError.FailedToGetPlayerProfileImage.errorCode(), it.localizedMessage, null)
    }
  }

  //region EventChannel.StreamHandler Methods
  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
    gamesSignInClient.isAuthenticated.addOnCompleteListener { isAuthenticatedTask: Task<AuthenticationResult> ->
      val isAuthenticated = isAuthenticatedTask.isSuccessful &&
              isAuthenticatedTask.result.isAuthenticated
      if (isAuthenticated) {
        playersClient.currentPlayer.addOnSuccessListener { player ->
          var playerData = PlayerData(
            player.displayName,
            player.playerId,
            null
          )
          val handler = CoroutineExceptionHandler { _, _ ->
            events?.success(gson.toJson(playerData) ?: "")
          }
          CoroutineScope(Dispatchers.Main + handler).launch {
            val image = player.iconImageUri?.let {
              AppImageLoader().loadImageFromUri(activityPluginBinding.activity, it)
            }
            playerData = playerData.copy(iconImage = image)
            events?.success(gson.toJson(playerData) ?: "")
          }
        }.addOnFailureListener {
          events?.error(PluginError.FailedToAuthenticate.errorCode(), "", null)
        }
      } else {
        events?.error(PluginError.FailedToAuthenticate.errorCode(), "", null)
      }
    }
  }

  override fun onCancel(arguments: Any?) {
    eventSink = null
  }
  //endregion

  //endregion

  //region Private Methods
  //region PendingOperation
  private fun finishPendingOperationWithSuccess() {
    pendingOperation?.result?.success(Messages.SUCCESS)
    pendingOperation = null
  }

  private fun finishPendingOperationWithError(errorCode: String, errorMessage: String) {
    eventSink?.error(PluginError.FailedToAuthenticate.errorCode(), errorMessage, null)
    pendingOperation?.result?.error(errorCode, errorMessage, null)
    pendingOperation = null
  }
  //endregion
  //endregion

  private fun mapAdditionalScopes(raw: List<String>?): List<AuthScope> {
    if (raw.isNullOrEmpty()) {
      return emptyList()
    }
    val mapped = mutableListOf<AuthScope>()
    raw.forEach { value ->
      when (value.trim().uppercase(Locale.US)) {
        "EMAIL" -> mapped.add(AuthScope.EMAIL)
        "PROFILE" -> mapped.add(AuthScope.PROFILE)
        "OPEN_ID", "OPENID" -> mapped.add(AuthScope.OPEN_ID)
      }
    }
    return mapped
  }
}
