package ai.mobilecore.runtime

/** Package-scoped broadcast contract used to report the real runtime load result to the UI. */
object ModelLoadStatusContract {
    const val ACTION = "ai.mobilecore.action.MODEL_LOAD_STATUS"
    const val EXTRA_STATE = "state"
    const val EXTRA_MODEL_PATH = "model_path"
    const val EXTRA_MODEL_ID = "model_id"
    const val EXTRA_MESSAGE = "message"

    const val STATE_LOADING = "loading"
    const val STATE_LOADED = "loaded"
    const val STATE_FAILED = "failed"
}
