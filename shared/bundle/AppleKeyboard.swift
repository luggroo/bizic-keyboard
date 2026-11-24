#if os(iOS)
import UIKit

public extension TujiaDictionary {
    /// For a UIInputViewController, pass textDocumentProxy and replace the UI's
    /// current draft with the returned suffix. A stale selection writes nothing.
    func commit(_ candidate: WordCandidate, in draft: String,
                to editor: UITextDocumentProxy) -> String? {
        guard let picked = selection(candidate, in: draft,
                                     before: editor.documentContextBeforeInput ?? "",
                                     after: editor.documentContextAfterInput ?? "") else { return nil }
        editor.insertText(picked.text)
        return picked.remainingInput
    }
}
#endif
