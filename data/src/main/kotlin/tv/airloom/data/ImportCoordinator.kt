package tv.airloom.data

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Shared gate for preparation, publication, source deletion and offset changes. */
@Singleton
class ImportCoordinator @Inject constructor() {
    internal val mutex = Mutex()
}
