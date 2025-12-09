/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "document.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <stddef.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "file.h"
#include "fpdfview.h"
#include "linux_fileops.h"
#include "logging.h"
#include "page.h"

using pdfClient::Document;
using pdfClient::FileReader;
using pdfClient::LinuxFileOps;
using pdfClient::Page;
using std::string_view;

namespace {

#define LOG_TAG "pdf/apk/jni/pdfClient/document_test.cc"

const std::string kTestdata = "testdata";
const std::string kSekretNoPassword = "sekret_no_password.pdf";
const std::string kSecretWithPassword = "sekret_password_banana.pdf";
const std::string kPassword = "banana";
const std::string kIncreasingDimPDF = "reorder_pdf.pdf";

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTestFile(std::string filename) {
    return GetTestDataDir() + "/" + kTestdata + "/" + filename;
}

std::string GetTempFile(std::string filename) {
    return GetTestDataDir() + "/" + filename;
}

std::unique_ptr<Document> LoadDocument(string_view path, const char* password = nullptr) {
    LinuxFileOps::FDCloser fd(open(path.data(), O_RDONLY));
    CHECK_GT(fd.get(), 0);
    std::unique_ptr<Document> document;
    CHECK_EQ(pdfClient::LOADED, Document::Load(std::make_unique<FileReader>(std::move(fd)), password,
                                               /* closeFdOnFailure= */ true, &document))
            << "could not load " << path << " with password " << (password ? password : "nullptr");
    return document;
}

void compareDocuments(const std::shared_ptr<Page> page_orig,
                      const std::shared_ptr<Page> page_copied) {
    static constexpr int kMaxDimension = 1024;
    CHECK_GT(page_orig->Width(), 0) << "0 page width";
    CHECK_GT(page_orig->Height(), 0) << "0 page height";
    const float scale_orig =
            static_cast<float>(kMaxDimension) / std::max(page_orig->Width(), page_orig->Height());
    size_t width_orig = static_cast<size_t>(page_orig->Width() * scale_orig);
    size_t height_orig = static_cast<size_t>(page_orig->Height() * scale_orig);

    CHECK_GT(page_copied->Width(), 0) << "0 page width";
    CHECK_GT(page_copied->Height(), 0) << "0 page height";
    const float scale_copied = static_cast<float>(kMaxDimension) /
                               std::max(page_copied->Width(), page_copied->Height());
    size_t width_copied = static_cast<size_t>(page_copied->Width() * scale_copied);
    size_t height_copied = static_cast<size_t>(page_copied->Height() * scale_copied);

    ASSERT_EQ(width_orig, width_copied);
    ASSERT_EQ(height_orig, height_copied);
    ASSERT_EQ(scale_orig, scale_copied);
}

void loadDocumentWithoutPassword(std::string fpath) {
    // Expect to fail for lack of password.
    LinuxFileOps::FDCloser in(open(fpath.c_str(), O_RDONLY));
    ASSERT_GT(in.get(), 0);
    std::unique_ptr<Document> should_fail;
    auto fr = std::make_unique<FileReader>(std::move(in));
    CHECK_EQ(pdfClient::REQUIRES_PASSWORD,
             Document::Load(std::move(fr), nullptr, /* closeFdOnFailure= */ true, &should_fail))
            << "should not have been able to load copy of " << kSecretWithPassword
            << " without password";
}

void comparePDFPagesDimensions(std::string original_doc_name, std::shared_ptr<Document> doc,
                               std::vector<int> page_order) {
    std::unique_ptr<Document> original_doc = LoadDocument(GetTestFile(original_doc_name));
    for (int i = 0; i < doc->NumPages(); ++i) {
        EXPECT_EQ(doc->GetPage(i)->Width(), original_doc->GetPage(page_order[i])->Width());
        EXPECT_EQ(doc->GetPage(i)->Height(), original_doc->GetPage(page_order[i])->Height());
    }
}

TEST(Test, CloneWithoutEncryption) {
    std::unique_ptr<Document> doc =
            LoadDocument(GetTestFile(kSecretWithPassword), kPassword.c_str());
    std::string cloned_path = GetTempFile("cloned.pdf");
    LinuxFileOps::FDCloser out(open(cloned_path.c_str(), O_RDWR | O_CREAT | O_APPEND, 0600));
    ASSERT_GT(out.get(), 0);
    ASSERT_TRUE(doc->CloneDocumentWithoutSecurity(std::move(out)));
    std::unique_ptr<Document> cloned = LoadDocument(cloned_path);
    compareDocuments(doc->GetPage(0), cloned->GetPage(0));
}

TEST(Test, SaveAs) {
    std::unique_ptr<Document> doc_orig =
            LoadDocument(GetTestFile(kSecretWithPassword), kPassword.c_str());
    std::string copied_path = GetTempFile("copied.pdf");
    LinuxFileOps::FDCloser out(open(copied_path.c_str(), O_RDWR | O_CREAT | O_APPEND, 0600));
    ASSERT_GT(out.get(), 0);
    ASSERT_TRUE(doc_orig->SaveAs(std::move(out)));
    loadDocumentWithoutPassword(copied_path);
    // Should load with same password.
    std::unique_ptr<Document> copied = LoadDocument(copied_path, kPassword.c_str());
    compareDocuments(doc_orig->GetPage(0), copied->GetPage(0));
}

/*
 * Tests the retention of std::shared_ptr<Page> as requested.
 */
TEST(Test, GetPageTest) {
    std::unique_ptr<Document> doc = LoadDocument(GetTestFile(kSekretNoPassword), nullptr);
    // retain == false so should be a new copy each time
    std::shared_ptr<Page> page_zero_copy_one = doc->GetPage(0);
    std::shared_ptr<Page> page_zero_copy_two = doc->GetPage(0);
    EXPECT_NE(page_zero_copy_one, page_zero_copy_two);

    // retain == true so should get the same ptr
    std::shared_ptr<Page> page_zero_copy_three = doc->GetPage(0, true);
    std::shared_ptr<Page> page_zero_copy_four = doc->GetPage(0, true);
    EXPECT_EQ(page_zero_copy_three, page_zero_copy_four);

    // since it's already retained, shouldn't matter if we request with
    // retain == false, should still get same one
    std::shared_ptr<Page> page_zero_copy_five = doc->GetPage(0);
    EXPECT_EQ(page_zero_copy_four, page_zero_copy_five);
}

TEST(Test, movePagesTest_singlePage) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));
    ASSERT_NE(doc, nullptr);

    std::vector<std::pair<int, int>> initial_page_dimensions = {
            {60, 80}, {120, 160}, {180, 240}, {240, 320}, {300, 400}};
    std::vector<int> initial_page_order = {0, 1, 2, 3, 4};

    // verify initial page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);

    std::vector<int> pageIndicesToMove = {2};
    int destinationIndex = 4;
    ASSERT_TRUE(doc->MovePages(pageIndicesToMove, destinationIndex));

    std::vector<int> new_page_order = {0, 1, 3, 4, 2};
    // verify new page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, new_page_order);
}

TEST(Test, movePagesTest_multiplePages) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));
    ASSERT_NE(doc, nullptr);

    std::vector<std::pair<int, int>> initial_page_dimensions = {
            {60, 80}, {120, 160}, {180, 240}, {240, 320}, {300, 400}};
    std::vector<int> initial_page_order = {0, 1, 2, 3, 4};

    // verify initial page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);

    std::vector<int> pageIndicesToMove = {4, 2, 1};
    int destinationIndex = 1;
    ASSERT_TRUE(doc->MovePages(pageIndicesToMove, destinationIndex));

    std::vector<int> new_page_order = {0, 4, 2, 1, 3};

    // verify new page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, new_page_order);
}

TEST(Test, movePagesTest_invalidSourceIndex) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));
    ASSERT_NE(doc, nullptr);

    std::vector<std::pair<int, int>> initial_page_dimensions = {
            {60, 80}, {120, 160}, {180, 240}, {240, 320}, {300, 400}};
    std::vector<int> initial_page_order = {0, 1, 2, 3, 4};

    // verify initial page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);

    // pageIndicesToMove contains an out-of-bounds index 5
    std::vector<int> pageIndicesToMove = {0, 5};
    int destinationIndex = 2;
    ASSERT_FALSE(doc->MovePages(pageIndicesToMove, destinationIndex));
    // Verify pages were not reordered
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);
}

TEST(Test, movePagesTest_outOfBoundsDestIndex) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));
    ASSERT_NE(doc, nullptr);

    std::vector<std::pair<int, int>> initial_page_dimensions = {
            {60, 80}, {120, 160}, {180, 240}, {240, 320}, {300, 400}};
    std::vector<int> initial_page_order = {0, 1, 2, 3, 4};

    // verify initial page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);

    // destinationIndex is out-of-bounds
    std::vector<int> pageIndicesToMove = {0, 1};
    int destinationIndex = 6;
    ASSERT_FALSE(doc->MovePages(pageIndicesToMove, destinationIndex));
    // Verify pages were not reordered
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);
}

TEST(Test, movePagesTest_numberOfPagesToMoveIsGreaterThanAvailableSlotsAfterDestinationIndex) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));
    ASSERT_NE(doc, nullptr);

    std::vector<std::pair<int, int>> initial_page_dimensions = {
            {60, 80}, {120, 160}, {180, 240}, {240, 320}, {300, 400}};
    std::vector<int> initial_page_order = {0, 1, 2, 3, 4};

    // verify initial page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);

    // pageIndicesToMove.size() is greater than (pageCount - destIndex)
    std::vector<int> pageIndicesToMove = {4, 3, 2, 1, 0};
    int destinationIndex = 3;
    ASSERT_FALSE(doc->MovePages(pageIndicesToMove, destinationIndex));
    // Verify pages were not reordered
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);
}

TEST(Test, movePagesTest_duplicateSourceIndex) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));
    ASSERT_NE(doc, nullptr);

    std::vector<std::pair<int, int>> initial_page_dimensions = {
            {60, 80}, {120, 160}, {180, 240}, {240, 320}, {300, 400}};
    std::vector<int> initial_page_order = {0, 1, 2, 3, 4};

    // verify initial page dimensions
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);

    // pageIndicesToMove contains duplicate indices
    std::vector<int> pageIndicesToMove = {4, 1, 4};
    int destinationIndex = 1;
    ASSERT_FALSE(doc->MovePages(pageIndicesToMove, destinationIndex));
    // Verify pages were not reordered
    comparePDFPagesDimensions(kIncreasingDimPDF, doc, initial_page_order);
}

TEST(Test, deleteSinglePageTest) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));

    int initial_page_count = doc->NumPages();
    EXPECT_EQ(initial_page_count, 5);

    std::vector<int> pageIndicesToDelete = {1};
    ASSERT_TRUE(doc->DeletePages(pageIndicesToDelete));

    ASSERT_EQ(doc->NumPages(), initial_page_count - 1);

    std::vector<int> expected_order = {0, 2, 3, 4};

    comparePDFPagesDimensions(kIncreasingDimPDF, doc, expected_order);
}

TEST(Test, deleteMultiplePagesTest) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));

    int initial_page_count = doc->NumPages();
    EXPECT_EQ(initial_page_count, 5);

    std::vector<int> pageIndicesToDelete = {1, 3, 2};
    ASSERT_TRUE(doc->DeletePages(pageIndicesToDelete));

    ASSERT_EQ(doc->NumPages(), initial_page_count - 3);

    std::vector<int> expected_order = {0, 4};

    comparePDFPagesDimensions(kIncreasingDimPDF, doc, expected_order);
}

TEST(Test, deleteAllPagesTest) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));

    int initial_page_count = doc->NumPages();
    EXPECT_EQ(initial_page_count, 5);

    std::vector<int> pageIndicesToDelete = {1, 3, 2, 0, 4};
    ASSERT_TRUE(doc->DeletePages(pageIndicesToDelete));

    ASSERT_EQ(doc->NumPages(), 0);
}

TEST(Test, deleteOutOfBoundsPageTest) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));

    int initial_page_count = doc->NumPages();
    EXPECT_EQ(initial_page_count, 5);

    std::vector<int> pageIndicesToDelete = {5, 6, -1};
    ASSERT_TRUE(doc->DeletePages(pageIndicesToDelete));

    // check no page deleted
    ASSERT_EQ(doc->NumPages(), 5);
}

TEST(Test, deleteDuplicateIndicesTest) {
    std::shared_ptr<Document> doc = LoadDocument(GetTestFile(kIncreasingDimPDF));

    int initial_page_count = doc->NumPages();
    EXPECT_EQ(initial_page_count, 5);

    std::vector<int> pageIndicesToDelete = {3, 3, 1, 1};
    ASSERT_TRUE(doc->DeletePages(pageIndicesToDelete));

    ASSERT_EQ(doc->NumPages(), initial_page_count - 2);

    std::vector<int> expected_order = {0, 2, 4};

    comparePDFPagesDimensions(kIncreasingDimPDF, doc, expected_order);
}

}  // namespace

int main(int argc, char** argv) {
    ::testing::InitGoogleTest(&argc, argv);
    FPDF_InitLibrary();
    int status = RUN_ALL_TESTS();
    // Destroy the library to keep the memory leak checker happy.
    FPDF_DestroyLibrary();
    return status;
}
