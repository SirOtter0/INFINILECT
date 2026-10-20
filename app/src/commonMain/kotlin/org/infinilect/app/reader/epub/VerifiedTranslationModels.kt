// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

/** Exact directional descriptors pinned to Foxlet 0.5.0. Metadata only; no model load.
 * Spanish/Catalan bridge translation is not a direct model or an enabled capability.
 * See the independently sourced registry fixture and docs/EPUB_TRANSLATION.md. */
internal val foxletVerifiedModels = listOf<TranslationModel>(
    TranslationModel(TranslationPair("en", "es"), "2.1:7d51237c0a07027dcd61643cfbbb0f8c48597d19907ef53d2cae9d6bec2cf25c,3b1c399511c01c84c36fae5c0524df44096288efdc8236e182b5c97d7ad2244c,5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad", 36576277L),
    TranslationModel(TranslationPair("es", "en"), "2.0:e2610211d3b9577d012638fe7e7e74ed7b4b708ce96b9e792e67c282a6492daa,4aed7734152ae0045d1a69ae49c86cfda18f53c61f90e95e1d1de1c7c7c3b033,5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad", 37014089L),
    TranslationModel(TranslationPair("en", "ca"), "2.0:8f2151a7fb75454f5167a8df8758ec02ab02c0302893851bad7caa6b729822fb,735881c99ff303e55eb2e92a3b86762523d5dd1a4797b2574b851062051426ee,64ebcb5f6cb5a954ced615e48a44a85e77a644e4910544014b299b8ee0fa1b1f", 36716532L),
    TranslationModel(TranslationPair("ca", "en"), "2.0:74880047539925630601d61aa6c29a0672046236ca6cf33eeaf6f6f6690e914e,b7f9c70823052f60c257eb1c4eff893abea3648aa9d1c23c8774e1e5ef061434,6d22da90905873c2793d0805e34e5c27901b16e7e7a7a427696696f67dfe1f0e", 36731612L),
)
