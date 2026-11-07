import XCTest
import Vision

final class LyricsReadingUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--lyrics-reading-ui-test", "-lyricsDisplayMode", "originalTranslationRomanization"] + arguments
        app.launch()
        return app
    }

    func testBrowsingSurvivesPlaybackAndWindowExpansionThenReturnsAndChangesTrack() throws {
        let app = launch()
        let list = app.scrollViews["fullLyrics.list"]
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["fullLyrics.line.22"].isSelected, "A nonzero window must highlight the array position for line 22")
        list.swipeUp(velocity: .slow)
        let resume = app.buttons["fullLyrics.returnToCurrent"]
        XCTAssertTrue(resume.waitForExistence(timeout: 3))
        let viewport = list.frame
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'fullLyrics.line.'"))
            .allElementsBoundByIndex.first {
                // A clipped row can remain hittable in the accessibility tree.
                // Measure a complete visible row as the actual reading anchor.
                $0.isHittable && $0.frame.minY >= viewport.minY && $0.frame.maxY <= viewport.maxY
            }
        XCTAssertNotNil(row)
        guard let row else { return }
        let identifier = row.identifier
        let lineNumber = try XCTUnwrap(identifier.split(separator: ".").last)
        let renderedOriginal = "Lyric line \(lineNumber)"
        let readingY = row.frame.minY
        attach(app, "lyrics-before-fill")
        try assertRendered(renderedOriginal, in: app)
        print("Reading anchor before fill: \(identifier) y=\(readingY), viewport=\(viewport)")
        app.buttons["fixture.fill"].tap()
        attach(app, "lyrics-immediate-after-fill")
        print("Reading anchor after fill: \(app.buttons[identifier].frame)")
        XCTAssertTrue(app.buttons["fullLyrics.line.22"].isSelected)
        XCTAssertTrue(resume.exists)
        XCTAssertTrue(app.buttons[identifier].isHittable, "The nonzero window row must remain visible after full lyrics arrive")
        XCTAssertEqual(app.buttons[identifier].frame.minY, readingY, accuracy: 12, "Expanding the same document must preserve the reading anchor")
        try assertRendered(renderedOriginal, in: app)
        try assertRendered("Translation \(lineNumber)", in: app)
        attach(app, "lyrics-rendered-after-fill")
        app.buttons["fixture.advance"].tap()
        XCTAssertTrue(resume.exists)
        XCTAssertTrue(app.buttons[identifier].isHittable, "Playback must not take over browsing")
        resume.tap()
        XCTAssertFalse(resume.exists)
        XCTAssertTrue(app.buttons["fullLyrics.line.32"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["fullLyrics.line.32"].isHittable)
        app.buttons["fixture.advance"].tap()
        XCTAssertTrue(app.buttons["fullLyrics.line.42"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["fullLyrics.line.42"].isHittable)
        list.swipeDown(velocity: .slow)
        XCTAssertTrue(resume.waitForExistence(timeout: 3))
        app.buttons["fixture.track"].tap()
        XCTAssertFalse(resume.exists)
        XCTAssertTrue(app.buttons["fullLyrics.line.3"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["fullLyrics.line.3"].isHittable)
        XCTAssertEqual(app.staticTexts["fixture.seeks"].label, "0")
        attach(app, "lyrics-real-browse-return-track")
    }

    func testDragReleaseDoesNotSeekAndOrdinaryTapSeeksOnce() {
        let app = launch()
        let list = app.scrollViews["fullLyrics.list"]
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        list.swipeUp(velocity: .slow)
        XCTAssertTrue(app.buttons["fullLyrics.returnToCurrent"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["fixture.seeks"].label, "0")
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'fullLyrics.line.'"))
            .allElementsBoundByIndex.first { $0.isHittable }
        XCTAssertNotNil(row)
        row?.tap()
        XCTAssertEqual(app.staticTexts["fixture.seeks"].label, "1")
        attach(app, "lyrics-real-single-seek")
    }

    func testStatisticsExplanationRendersInChineseAndEnglish() {
        for (language, expected) in [("zh-Hans", "30 秒"), ("en", "30 seconds")] {
            let app = launch(["--statistics", "-appLanguage", language])
            let explanation = app.staticTexts["playbackStats.explanation"]
            XCTAssertTrue(explanation.waitForExistence(timeout: 5))
            if !explanation.isHittable { app.scrollViews.firstMatch.swipeUp() }
            XCTAssertTrue(explanation.isHittable)
            XCTAssertTrue(explanation.label.contains(expected), explanation.label)
            attach(app, "statistics-\(language)")
            app.terminate()
        }
    }

    func testAccessibilitySizeInnerLyricsCanBrowseAndReturn() {
        let app = launch(["--large-type", "--long-lines"])
        let list = app.scrollViews["fullLyrics.list"]
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        XCTAssertTrue(list.isHittable)
        list.swipeUp(velocity: .slow)
        let resume = app.buttons["fullLyrics.returnToCurrent"]
        XCTAssertTrue(resume.waitForExistence(timeout: 3), "The inner lyric gesture must enter browsing")
        let page = app.scrollViews["fullLyrics.page"]
        if !resume.isHittable {
            // Start outside the inner list to move the surrounding compact page.
            let start = page.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.85))
            let end = page.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.25))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTAssertTrue(resume.isHittable, "Return must remain reachable at accessibility text sizes")
        XCTAssertGreaterThanOrEqual(resume.frame.height, 44)
        resume.tap()
        XCTAssertFalse(resume.exists)
        XCTAssertEqual(app.staticTexts["fixture.seeks"].label, "0")
        attach(app, "lyrics-accessibility-real-inner-drag-return")
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func assertRendered(_ text: String, in app: XCUIApplication) throws {
        let image = try XCTUnwrap(app.screenshot().image.cgImage)
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US"]
        try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        let visibleText = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        func normalized(_ value: String) -> String {
            value.lowercased().filter { $0.isLetter || $0.isNumber }
        }
        XCTAssertTrue(visibleText.contains { normalized($0).contains(normalized(text)) },
                      "The screenshot must actually render \(text); recognized: \(visibleText)")
    }
}
