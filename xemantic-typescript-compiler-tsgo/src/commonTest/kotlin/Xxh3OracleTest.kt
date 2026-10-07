/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.hash
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.hash128
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.hashString
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.hashString128
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import kotlin.test.Test

/**
 * `xxh3` against real Go (go1.27.1, github.com/zeebo/xxh3 v1.1.0 on amd64, so the long inputs went
 * through the library's AVX2 path): every length 0..300 (each branch boundary of the 64- and
 * 128-bit functions), the block boundaries 1023..2049, and 64 KiB / 1 MiB, over a byte pattern
 * that covers all 256 byte values; plus a few text strings. Regenerate with the throwaway program
 * in the round's scratch (`xxh3.Hash128(gen(n))`, `gen(n)[i] = byte(i*31 + (i>>8)*7 + 0x5a)`).
 */
class Xxh3OracleTest {

    private class V(val n: Int, val hi: ULong, val lo: ULong, val h64: ULong)

    private class S(val text: String, val hi: ULong, val lo: ULong, val h64: ULong)

    private fun gen(n: Int): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(((i * 31 + (i shr 8) * 7 + 0x5a) and 0xFF).toChar())
        return sb.toString()
    }

    @Test
    fun `HashString128 and HashString match Go at every length`() {
        val mismatches = ArrayList<String>()
        for (v in LENGTHS) {
            val s = gen(v.n)
            val h = hashString128(s)
            if (h.hi != v.hi || h.lo != v.lo) mismatches += "128 n=${v.n}"
            if (hashString(s) != v.h64) mismatches += "64 n=${v.n}"
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `the byte-slice entry points agree with the string ones`() {
        val mismatches = ArrayList<Int>()
        for (v in LENGTHS) {
            if (v.n > 4096) continue
            val b = goStringToBytes(gen(v.n))
            val h = hash128(b)
            if (h.hi != v.hi || h.lo != v.lo || hash(b) != v.h64) mismatches += v.n
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `text strings hash as their UTF-8 bytes`() {
        val mismatches = ArrayList<String>()
        for (v in TEXTS) {
            val s = GoString.fromUtf16(v.text)
            val h = hashString128(s)
            if (h.hi != v.hi || h.lo != v.lo || hashString(s) != v.h64) mismatches += v.text
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `Bytes is big-endian hi then lo`() {
        val h = hashString128("?")
        val b = h.bytes()
        val hex = (0 until 16).joinToString("") { b[it].toString(16).padStart(2, '0') }
        assert(hex == "13941ad4ddb2863f8b72305f19fe690b")
    }

    private companion object {
        val LENGTHS: List<V> = listOf(
            V(0, 0x99aa06d3014798d8uL, 0x6001c324468d497fuL, 0x2d06800538d394c2uL),
            V(1, 0xa26f5ff5290b016cuL, 0x2753d05a8f320003uL, 0x2753d05a8f320003uL),
            V(2, 0x1d16a6c9ccc6efccuL, 0x0955db7188322dcauL, 0x0955db7188322dcauL),
            V(3, 0xd204ffcac9a0024buL, 0x30cb8197e9510decuL, 0x30cb8197e9510decuL),
            V(4, 0xdb0ce8872dc25f32uL, 0x7f2e3df47f29fcdfuL, 0xc20f4393c135418auL),
            V(5, 0x3a7c8ffd1613bb73uL, 0x4360e38513de5f66uL, 0x14194ec2a42204ecuL),
            V(6, 0x342f16e2022168b6uL, 0xfa4ca1122533deceuL, 0x9bb3a6faebad6edauL),
            V(7, 0x37742e3ea1adb110uL, 0xe85d11bc942c03a1uL, 0x0dc3c03f823e4b9fuL),
            V(8, 0x6fb229c1e2d03751uL, 0xc2eff48961cacf6fuL, 0xf75e794820059231uL),
            V(9, 0x046b8fd2d767734fuL, 0x06b700b63ff6af1duL, 0xfed3dbd09b54fa19uL),
            V(10, 0x8e260405baba2026uL, 0xf623c10d5caa5b96uL, 0x9fb0bd1692b2fccauL),
            V(11, 0x0ca4e24ce768b26auL, 0xecd12cb7c130a244uL, 0xd1d531200303517fuL),
            V(12, 0x8b53c833daf9b4b1uL, 0x555f0aca9c9e655cuL, 0x9d81841d978aa151uL),
            V(13, 0x8bf6ac70ea46c69buL, 0x1f5931476c4fe230uL, 0xb1100eb847f40734uL),
            V(14, 0x97e1be388838c016uL, 0x88d3aead126c9ef1uL, 0x870305122df9a9c0uL),
            V(15, 0xf05b93070fdc148duL, 0x03f4434366eae4bfuL, 0xf1dd7d732b61d93fuL),
            V(16, 0xbc185d96770b12e6uL, 0x8a43f5a6d88417efuL, 0xad2361b12ad521eeuL),
            V(17, 0xf5940ac65615cf0auL, 0xb056e2a25f282b5euL, 0xa40b4bff19c0ec7buL),
            V(18, 0x236727c8ff819256uL, 0x24e548680184c961uL, 0xbb2857703d0245b7uL),
            V(19, 0x2305c97fe50d7321uL, 0xb19a644d8a62bcf6uL, 0xb42712ad89890606uL),
            V(20, 0x53e9c3de397ef781uL, 0x5cbb259bf96ee51auL, 0xae3b1ab38d093840uL),
            V(21, 0x5e525a32871fe258uL, 0xc2a9c7f2a1375fd5uL, 0x501f83a0e8843264uL),
            V(22, 0xe1ecbddf50f1aab7uL, 0xb1a3f85a895d5df1uL, 0x4be7c5f18a2347b3uL),
            V(23, 0xc766155dfbc03a67uL, 0xe48635f1f04f5502uL, 0x0889220ba02f6926uL),
            V(24, 0x08cadaf17029b484uL, 0xc5729ea3cd195024uL, 0x3ddb0e51597ae535uL),
            V(25, 0x1438c8bc998f905cuL, 0x97feb66181115fdcuL, 0x72eb9a646bf3062euL),
            V(26, 0xee0e037a89528599uL, 0x7dde34087fb99bc6uL, 0x7676402836925f75uL),
            V(27, 0x326b576f1364bf90uL, 0x883f53d581d7c5f7uL, 0x8ec34fa2b9d34da1uL),
            V(28, 0x0aec7b6e0417792fuL, 0xdf746a30a83ae0dfuL, 0x67c8f1765f6d0fc8uL),
            V(29, 0x83c73e7c6becf5f5uL, 0x8210e5795f98fc80uL, 0x81084b1d8ce53f00uL),
            V(30, 0xa35ff10dec3d9040uL, 0x3a3f34c9926d9810uL, 0x982e4a31f042283fuL),
            V(31, 0xee1302efb5c0f3efuL, 0x42cd6a617d7dd2ceuL, 0xc1cc8b3b028b8411uL),
            V(32, 0xb7e608f3f95b3532uL, 0xbe57841c85bddbe7uL, 0x6c25c6e75394990duL),
            V(33, 0xd5c2ae1a71897da7uL, 0x2f8604b41ae52079uL, 0x0dd8a65d480b89f4uL),
            V(34, 0xa97300d370e75b63uL, 0x0d2e969efae73c0buL, 0xaf1f64891b73147euL),
            V(35, 0x981e06ebe551c232uL, 0xc4ec106362afe03euL, 0xf2fb9b7c78445566uL),
            V(36, 0x79c65619a2ae8092uL, 0x59cc41fe7f40bc5auL, 0x3cd1b30ba739b38auL),
            V(37, 0x111bfcdc160778bauL, 0xd869f9ae5d739882uL, 0x4c8d0fb833272b0euL),
            V(38, 0xbd1adadf29c8d972uL, 0x330783ba2d20e3acuL, 0x8949dec2fe9b86dfuL),
            V(39, 0x5b8724a734fea25duL, 0xa943c290c751b077uL, 0x4bf581db33ec6a3euL),
            V(40, 0xc0589620be8787b1uL, 0x5c3d196303efa6f8uL, 0x3dd3c30faa31f007uL),
            V(41, 0x4f48cc2373ce9c44uL, 0x3ed20876a3efb3fcuL, 0x81d9a149ad8bbbabuL),
            V(42, 0xa2ccf4b4525f99aduL, 0x77e889074a77598auL, 0x45851f2da6ad6269uL),
            V(43, 0x6b5eb6bf3fb5f939uL, 0xce2e87b3b630dce2uL, 0x0fc12f949114abd8uL),
            V(44, 0x76603654f158b862uL, 0x6ccddaa4f1aa6acfuL, 0x78b81ac5d5cbf246uL),
            V(45, 0x29a88bb84b5e98b8uL, 0x5121d5100a3c3b2auL, 0x9851f3844b169bb4uL),
            V(46, 0x301cf644d447ba59uL, 0xdf13f2c2b2af68a8uL, 0x3ca01f204e7338d0uL),
            V(47, 0x95f2c3548a9b36c7uL, 0x34cad67355015c05uL, 0x6002b938ae4e7ec6uL),
            V(48, 0x7375f6ac8ee9b417uL, 0x37029dec393d17e0uL, 0x7ad7381f1cd8e16duL),
            V(49, 0x1d2ae4b7f43bb55fuL, 0x63df1d10ee1f7dd1uL, 0x014d9b5e7f9fec41uL),
            V(50, 0xaa4d62ece84b0d65uL, 0x5be3c4d5833a2880uL, 0x3b846a341e0cc0b9uL),
            V(51, 0x53ea6e4f1987971fuL, 0x05ff588fbffc4aafuL, 0x3697b461704ca8bauL),
            V(52, 0xe3ce013418c2e23duL, 0x9f2c0ce6470fdb66uL, 0x3e80712c5939d6c1uL),
            V(53, 0x72e3fd897e0ebc6duL, 0x37d93aec7c1e924buL, 0x88fd0d46eeed3e1buL),
            V(54, 0xd9bbaff979131ad3uL, 0x48f8df8b4627eb7auL, 0x2a9c14af3f436985uL),
            V(55, 0x61d1054a648b49d4uL, 0xf05f00a55a62defbuL, 0x39f497b8363913a3uL),
            V(56, 0xa8d42a67e4194713uL, 0x23c9aaefdf37ee9buL, 0x13c9440d1809b572uL),
            V(57, 0xd8ca3725dbb7518fuL, 0xfa2054486e234c42uL, 0x73851f268a63b80duL),
            V(58, 0x64e4e82dbb8b6609uL, 0x49d587489af5a0deuL, 0x4ec63ee1790d7f54uL),
            V(59, 0x56e25f01e0624d91uL, 0xea8f01bdaaf1f57cuL, 0x2edb0a8203654d64uL),
            V(60, 0xd2840b0ad89d93e5uL, 0xe6a02aeb0b9641c3uL, 0xd7760a72406c6cfeuL),
            V(61, 0xf56ab9c633876e9buL, 0xa2a1db58c09ee33buL, 0x282bc0d4260ad780uL),
            V(62, 0xc78e834100a1cbd1uL, 0x3c643c0fda517850uL, 0xbb2de9cb6ae67225uL),
            V(63, 0xdc3b271616b1b85euL, 0x17e9088e83de77a5uL, 0x264f0bb164c90609uL),
            V(64, 0x0d9cd9ceb41b7f82uL, 0xaa3938f1443acf17uL, 0xdf22ec7a154fd9b6uL),
            V(65, 0x88f7c70a93e18130uL, 0xfe5717aa2424b8dcuL, 0x628c5e97e733e8f8uL),
            V(66, 0xb31588ca4122e92buL, 0xa9fa0ca87197ead7uL, 0x55cb8c7da023af11uL),
            V(67, 0xf82440e7bddc7d68uL, 0xaf1390f838db7eb9uL, 0x4e23d2cf00063222uL),
            V(68, 0x1a0fb4d685618cf3uL, 0xa4861a862bbc3c6fuL, 0x9fd2b30767b5a98euL),
            V(69, 0x0e1114f8c5112201uL, 0x00d09c78e158e27fuL, 0x346bc56920b01f40uL),
            V(70, 0xaf6c0770aebcdc8duL, 0x176c7d1b2ccf6a14uL, 0xd3d8087ab8531239uL),
            V(71, 0x4a2111f87aeda021uL, 0x2e50fed24acddf57uL, 0x648a8b06220cf58buL),
            V(72, 0x9b67d29c04373844uL, 0xd3af79c30aa819e3uL, 0x8043ee1eadb21301uL),
            V(73, 0x89924ae79e13a536uL, 0xb12344f82bc0f622uL, 0xf8dfa37bacc242cfuL),
            V(74, 0xc010f6d0ac65bd80uL, 0x91e85a7f6cb34b35uL, 0xdb041952d4b9998euL),
            V(75, 0x606ae214745bc1a1uL, 0x8ee4874bb0a53623uL, 0xbe870004a420b3bauL),
            V(76, 0x589ca99004123f59uL, 0x8c637e43a44d594buL, 0xbf6ac7c3ea0e86f7uL),
            V(77, 0x94abee344dbb512euL, 0x514be4f416e58585uL, 0x3ef6698139259bbauL),
            V(78, 0xb7971f5b678724a7uL, 0x00f4995afe5cc544uL, 0xe0c45c8688ac6a6cuL),
            V(79, 0x8a9ebed34385dc6duL, 0xc2a7bd3d4fff5fd8uL, 0x825a9df497b139f7uL),
            V(80, 0x140d84cb6a74dbe6uL, 0xa40222272b7fbc8auL, 0x1cc98f93e79f7402uL),
            V(81, 0xf55f9478bd701c5auL, 0xd2ee4eb57d3df590uL, 0x539e2f223e8547b1uL),
            V(82, 0x78e17831c8909338uL, 0xa00eb27529a85249uL, 0x7b21d8752b65245duL),
            V(83, 0x804bde8dc8820ec2uL, 0x85e93e9e23fe0176uL, 0x092bfbeb9b533040uL),
            V(84, 0x9950d1401c902e66uL, 0x507b0eda20079e7fuL, 0x2ff0de8078162bfbuL),
            V(85, 0xbf7e4fc26c000761uL, 0xa3cc4bb3dc166d14uL, 0xff10ebc373567d36uL),
            V(86, 0x57b3d977fc248045uL, 0x9368bc6db5b6c39euL, 0x05439de681480188uL),
            V(87, 0xbae7052991fab83cuL, 0xf6a63a3e10f42b98uL, 0x9f8569f2fe071caauL),
            V(88, 0xab94d1a64147edbbuL, 0x3fdb6684eb847890uL, 0x09049a2c964704acuL),
            V(89, 0xd2fcd44c0c4eb4b5uL, 0xca890d16799d0527uL, 0x4fe5b8f66605fafcuL),
            V(90, 0xfd5e890f888522e1uL, 0x600821b934fbcd32uL, 0xdce3bdbfffc31afeuL),
            V(91, 0xf4dea287d3d9d8c4uL, 0x11adb49e28c678a8uL, 0xb078012e9707f50duL),
            V(92, 0x10fb3b6a1dd232f7uL, 0x0c3ed103b2bd6e4euL, 0x964d1d52cfc963c4uL),
            V(93, 0xb2aed45a3b156a31uL, 0xfd0cc3994a81673buL, 0xf3b8ba48827ff26buL),
            V(94, 0xe4f415ea09d05b52uL, 0x3f0dc017d2730fbeuL, 0xb50741c7624f3a3fuL),
            V(95, 0x95a6635cc5ec4f34uL, 0x6d700c9f24a0687fuL, 0x439a4c0fd855ae0cuL),
            V(96, 0xa301586f981daa9buL, 0x7d7ed92920a55d30uL, 0x65973448de164826uL),
            V(97, 0x0a1f92e1d495c747uL, 0xc1707a94fe17f99cuL, 0x79fe4ea9a85a3145uL),
            V(98, 0x4f7e15b9b27abcf4uL, 0x48b03c12fe1e864buL, 0x43650e9397bd8c40uL),
            V(99, 0x9a99372e71987512uL, 0x8aa1d511feaba399uL, 0xd69d2ee73dab4227uL),
            V(100, 0x7550c3d0cdc6c420uL, 0xba91fe0c0c0358dauL, 0xa0365c30e77b9adbuL),
            V(101, 0xd9962b687651934cuL, 0x8d9c958f2bde653duL, 0xcf48de53cf5edad8uL),
            V(102, 0x01afb5ec458ea749uL, 0x3f5f15e73243a97buL, 0x4062dc04de1fb21fuL),
            V(103, 0x7a01d7711889a935uL, 0xb86b12a302fcd7fduL, 0x46ec0b41df2444b6uL),
            V(104, 0x933a8c01cc915fc2uL, 0xbcba201d159fe2c5uL, 0xc654744763d1c199uL),
            V(105, 0x391480b1aa3243a9uL, 0x4736d343a6757462uL, 0xd05e588f3958cd5euL),
            V(106, 0x5cb04f4fe02b5950uL, 0x08d10fd68dfa4d94uL, 0x9ee858f77906e078uL),
            V(107, 0xb36c0aa3e6d5a481uL, 0x017bb69b6cfb7354uL, 0x29485768f6532821uL),
            V(108, 0x6e2caeba62514010uL, 0xf9f4c09bfdcb4c0buL, 0x15327ad8b27187feuL),
            V(109, 0x1e959e22102728e8uL, 0x0620dad2dc5029fduL, 0xf65b20cabf18e64duL),
            V(110, 0xc302311da236d89fuL, 0x2d056ca4a2915364uL, 0x87ce272bf0afd57cuL),
            V(111, 0xca130655540b7a3duL, 0xacba95c5f3da441euL, 0xab22313f9e4c79fauL),
            V(112, 0xa9169c735d07b3c3uL, 0xcda0bec34b77d626uL, 0x6ca456fd17db6326uL),
            V(113, 0xb0e2679c8d892d1duL, 0x55f090a55d5eac4auL, 0xbecfde59008bdd1auL),
            V(114, 0x1bc25fd4809e388cuL, 0xea0bae17a464ab4auL, 0xa56927b58e819ed8uL),
            V(115, 0x906582e0c7ab8e05uL, 0xfe9f17f5ea1a4a05uL, 0x300a05c2caef157buL),
            V(116, 0x73185a5baf69d43buL, 0x3d930491381debf7uL, 0x8861aed037ba469duL),
            V(117, 0xc535e90eecc153f9uL, 0xe646e2493bf05caduL, 0x9b1d6daddac85964uL),
            V(118, 0xb24cd67477b802deuL, 0x0e35f454e3d028f4uL, 0x20e550203f5438a5uL),
            V(119, 0xa988af9dae816d0buL, 0x453b087bfe463b17uL, 0x7c9945e97b3e6cb6uL),
            V(120, 0xc7f994e106afcc25uL, 0xb57b0af823f79c78uL, 0xda9e849af1a12d61uL),
            V(121, 0xc707416c319c7a52uL, 0x5dc52bf9d13e6738uL, 0x33353626f55984ceuL),
            V(122, 0x2cc07bdf763e9af5uL, 0x46e708719b35daf9uL, 0x97ba1c70e15dab68uL),
            V(123, 0xa520d8b6f3915cd9uL, 0x8b48f0439bf980ceuL, 0x107b40beae978752uL),
            V(124, 0x11a608866b5fdc4fuL, 0x2719218a14ac4c8duL, 0xf9975c4a481e5ebauL),
            V(125, 0x0eeb51cef2aa0ac7uL, 0xd37da66e0e796a22uL, 0x4e93bb34bad31cc4uL),
            V(126, 0x0d2cbb6e680c6b32uL, 0x39963cb2c86a0d4cuL, 0x8772a4c312efbdbfuL),
            V(127, 0x78db319bdc216866uL, 0x788910886c5b83a2uL, 0x93fba865bdbf914buL),
            V(128, 0xd595037cde8d844auL, 0xfa051cafbe33272euL, 0xb3bbf93205ff6967uL),
            V(129, 0x70dfa70dd0a04992uL, 0x2b1fb50f9f8326a5uL, 0xe176f476aebb40aauL),
            V(130, 0xa42d2b57dfcb0828uL, 0xe48f0ef4b65ecafeuL, 0xd76488db12d89b1fuL),
            V(131, 0x9240b134a6fb53aauL, 0xada416590f7d19bduL, 0x53841a763df66af3uL),
            V(132, 0xf021db25a7aae73buL, 0x1b70cdbb92891377uL, 0x18f44da060cc51fauL),
            V(133, 0x3cb58ae2c3473547uL, 0x0c02d278c7df1aa5uL, 0x412449172882b26buL),
            V(134, 0x9b01d0741d53da59uL, 0x33c648db63a86b0auL, 0x3dcb1f1fa2f1f259uL),
            V(135, 0x5ef3fc1e3c366a4buL, 0x82449b3d41b5ee3euL, 0x41e466cb00138b17uL),
            V(136, 0x522ff536ba57eba9uL, 0xd9be501e52bbd9c4uL, 0x3c7ac4e08aa7b6ffuL),
            V(137, 0x19d6adfa282e58aeuL, 0x62824d3e30535ec4uL, 0x85fe4e2a0568be7auL),
            V(138, 0xa9959aac4566fb2cuL, 0xa79f8df6df69ad40uL, 0xc8caf9c325fe4315uL),
            V(139, 0x2d05e9ccb5c67cdcuL, 0x07254152053643d7uL, 0xb1171a5a8ff30c27uL),
            V(140, 0x16a9ff788dd72827uL, 0x55d12b47e213523auL, 0xa9bc841129568802uL),
            V(141, 0x501368152dcbd916uL, 0x1e5dfac8b54c856fuL, 0xdcfd7b91382745f8uL),
            V(142, 0x5e94a8eb05a26be1uL, 0xbb03fe33ef3d3b0duL, 0xeaac4e12f1a4949auL),
            V(143, 0x43212589d57c5defuL, 0x9581b7d0d5dab76buL, 0x04f987b5e85150a4uL),
            V(144, 0x1b83c9fdb7605b51uL, 0xde578237845d9042uL, 0x88971707fdd43abfuL),
            V(145, 0xf4d14a55468252cfuL, 0x333d911a49de2744uL, 0x2250c751a6bb9bd4uL),
            V(146, 0x69735aafede9f222uL, 0xa63a11a0fd341777uL, 0xa3c6a84799f5ee1buL),
            V(147, 0x7a332ab83bf9914duL, 0xdc76e23571665933uL, 0x344c21f0c9ac5492uL),
            V(148, 0x95baaf1cf95e07b5uL, 0xbcd291f09913166auL, 0x55c0aa01fe370674uL),
            V(149, 0x5ed2d0fa768c6ccfuL, 0x0a32718c07a03a8auL, 0x1ac28be63f0e804euL),
            V(150, 0x885e27f24ef00e00uL, 0x550564ed01ec3339uL, 0x5532fe13aa8c183buL),
            V(151, 0x0e95d5af3e7f4a07uL, 0xd3759cee773182cfuL, 0xfdfba291f5d01841uL),
            V(152, 0x57263d6c5bb6a835uL, 0xa89001d28ee31f2cuL, 0xcbbcb96c00346100uL),
            V(153, 0xc2fe8a2c323ee408uL, 0x3d0b59491164120buL, 0xbce6b16919cc79c2uL),
            V(154, 0xb1af43539cc5a4f2uL, 0xe7f2cbff731c0b0euL, 0x2b3f8e41fb3af15euL),
            V(155, 0x36a6d6d9a33d17bduL, 0xa272bd9adbb8a01cuL, 0x069815a2d2566e2euL),
            V(156, 0xaaccb7d3fe4e46c9uL, 0x7819d6c1588a45a1uL, 0x2d41d5eb20ac4509uL),
            V(157, 0xf2b32bf2e9df05ccuL, 0x56f85b0f74462f4euL, 0x0db860efff30af88uL),
            V(158, 0x6269cbbc62cb759fuL, 0x92453fc1643d4e49uL, 0x0a3f2ebc078cebeduL),
            V(159, 0xb212d80f40c4489duL, 0x899f2a81d7a2df9duL, 0xc14eeb9837538142uL),
            V(160, 0xc9c95e7e70d829c6uL, 0xef3004c8f94aa77buL, 0x3b5377f8b8e30712uL),
            V(161, 0x2a1907c58b841f12uL, 0x262164dfcffbcb89uL, 0x8d5c34fa99b191d0uL),
            V(162, 0x29c5a5f0a36c5263uL, 0x74f14a238f09d69cuL, 0xe5bfde7f12d328eeuL),
            V(163, 0xe0ee656405552b32uL, 0x40521f354ceb37d2uL, 0x8443f81f911e4c95uL),
            V(164, 0x6b41281c0210534buL, 0x00ec886f248aed25uL, 0xc7513634f00db9a7uL),
            V(165, 0xd5014081121d71d0uL, 0xf07bd4f69e6e4882uL, 0x0e35c0826339e7e3uL),
            V(166, 0xfda6b4ceed3795f2uL, 0xdbe847a7bfdc0585uL, 0xd9ba9174b004e97buL),
            V(167, 0x3cd0665b79b17462uL, 0x191c4d8eff61ccfbuL, 0x7e0d20ef5480fc9cuL),
            V(168, 0x9bd4a829f9e5b624uL, 0xaba2295335bd4915uL, 0x8c97c7f3b5fe3e1fuL),
            V(169, 0xa9e021c34741b9a6uL, 0xec904e51af5480c7uL, 0xaaad7a321e1d45a1uL),
            V(170, 0x1c939a407816e94auL, 0x7b9f01cdef57bea8uL, 0x2675c01b9b26f096uL),
            V(171, 0x17e0fd3c5fccd98duL, 0x348b444f326077e1uL, 0x1dfdae1763d63b6auL),
            V(172, 0x23827ff0713b3e6duL, 0xd8a5a46027c708ccuL, 0x5fddf1d51ba8627auL),
            V(173, 0x81c11edb64f65800uL, 0xbabdf4314a0f959buL, 0x9b4f6b10ad9a9a6buL),
            V(174, 0xebf174b61a53b51auL, 0x233fe2a5d0b3bae9uL, 0x2d583c031b76ca12uL),
            V(175, 0xf3721569dd7faf0euL, 0x6ab2d3911a752c11uL, 0x56488eaa88b0703auL),
            V(176, 0xc253f97117aae842uL, 0xfddae39720a35719uL, 0x9f4beaf944959908uL),
            V(177, 0xe5029bfca5390dcfuL, 0xbd6d80eacc71f286uL, 0x2f9e08a043d925afuL),
            V(178, 0x5886dd1bc8f5d749uL, 0x7db0ded93b64b51euL, 0x14bc671aaca7d115uL),
            V(179, 0xf78a379dc1c43f0duL, 0x80d92ef7e6c0e597uL, 0xa430ccbb2e26fda5uL),
            V(180, 0x4694931e283fae19uL, 0x038d64f4240cc5ccuL, 0xed0e3329cf4b8c1cuL),
            V(181, 0x070f43a85090374euL, 0xed8c8c42cc6a78d9uL, 0xc81305a57fa1e876uL),
            V(182, 0x0c1019a881c9c90buL, 0x80269f151d0500f0uL, 0x53e3ea4a2891304auL),
            V(183, 0xdf1b59c4d6faa68euL, 0x53cb01d306e119fbuL, 0xa099de5bfc059bb4uL),
            V(184, 0x0be25564d71c1531uL, 0x9e55bdae31d6ff13uL, 0xfae2310db912332auL),
            V(185, 0x5fe996d717a13555uL, 0x24547e872ab6655fuL, 0xed4d5d857dbdbff6uL),
            V(186, 0x36e3af231606b2a3uL, 0x16b91db10645a347uL, 0x50d48b8aca3b9e52uL),
            V(187, 0xc7b0d93f1e55db2auL, 0x5cdde2caf08a7e35uL, 0xdc8f2b913768a640uL),
            V(188, 0x027a545981b802acuL, 0x34c62fc0e9b3dec0uL, 0x1b7226ff947da5b7uL),
            V(189, 0x7e7f0e66ac3b21deuL, 0x142864ee7b2d7768uL, 0xfec1597999bc21cauL),
            V(190, 0x320d19ec3587c294uL, 0x40fbb255066bcea7uL, 0x9e2cd4a7fb554f7duL),
            V(191, 0x5a7cd9ccd1e5bcc1uL, 0x56967aeb9b9cdbe5uL, 0xdd6d7a948fb432c6uL),
            V(192, 0x4c8bbcd414ee17d5uL, 0xde5071a21cb5ab04uL, 0x7285ab38dca163c3uL),
            V(193, 0x0ff1fdd17d440c0auL, 0xa137b15cb0c3cbefuL, 0xad7df0a8f21058afuL),
            V(194, 0xb9c54c30f981ef9auL, 0x10df6a53a298f5c8uL, 0x5f4d1a1fbb4db8dduL),
            V(195, 0x18f16333a05ed3a9uL, 0xcfa83e33016fe4b2uL, 0x379b235dca97d7e0uL),
            V(196, 0x0f940e0ab7e16db2uL, 0x4972d48f87c746ecuL, 0x2bc06b84f23fcdf6uL),
            V(197, 0x7d3427be4ce29ad0uL, 0xd6ac00f5d3207106uL, 0xa57f062059d0ac8fuL),
            V(198, 0x6403940b1ca8c96auL, 0x0fda3b0774b9eb5cuL, 0x994dacad36dcd49buL),
            V(199, 0x6680274149eed9ecuL, 0xee012954312ee4c9uL, 0xcfce159dd614fd69uL),
            V(200, 0x98de19b4c607431euL, 0xbb17748ce4a9b24euL, 0xbdc899385be5cb28uL),
            V(201, 0x3a5e906ddabcd5d0uL, 0xa8ae0665653f5178uL, 0xa72fa9c8bf79723buL),
            V(202, 0x6b20d6cc3cbccf19uL, 0x84405abecb64cfecuL, 0x2c8465507edf0c0cuL),
            V(203, 0xf9a18607429bc6c5uL, 0xcafbdda371bc04fduL, 0xbffbe157c38d4619uL),
            V(204, 0x1a916cb1aae1334auL, 0x7d259fe4a9768724uL, 0xa725b7579c70c8f8uL),
            V(205, 0xf5ce2e0424f6d1d5uL, 0x56c8e559529a421auL, 0x22ff781c6ba9d8d6uL),
            V(206, 0x62586f79ecd8c2a7uL, 0x438728f99323a45buL, 0xd16d559488f756f2uL),
            V(207, 0x6b73ec94e74b2a1auL, 0x87df1df764c15a4euL, 0xc9eec401158fbf83uL),
            V(208, 0x98d2953f5bdf8d38uL, 0x34836be94242a6c9uL, 0x4ce95173234f391cuL),
            V(209, 0xaa6a2ec001537132uL, 0xd63e8350df3e246buL, 0x56ca96d127b973fcuL),
            V(210, 0xe10be76b80b0d67buL, 0x2158bb16b9cd2229uL, 0x9fc4575adfb0d3c2uL),
            V(211, 0x88be4cfc0a125e5auL, 0x5b44d2938becd22cuL, 0xa732f70dd0d5279duL),
            V(212, 0x34ab70aa536878b7uL, 0xcc871014f9caafc7uL, 0x2f582943d4304600uL),
            V(213, 0x28a27e44326368f8uL, 0xffc1bcbe62ac8261uL, 0x8df9a6bead175e29uL),
            V(214, 0x3d51bbe6b435f2c9uL, 0x0409306d050565f0uL, 0xa209ab17dda0a537uL),
            V(215, 0x48fff80a08e53f07uL, 0x0c80a792007d553cuL, 0x9a6287d003624a8auL),
            V(216, 0x87ca7e73e24372c9uL, 0x753e38880c1ea50buL, 0x1608f8526762c272uL),
            V(217, 0xc06d60940a2f5bf1uL, 0x46e39734d83be5ebuL, 0x821eddfd82dc2eb0uL),
            V(218, 0x6a9f20ded2362953uL, 0xad33fd9c179c6ce5uL, 0x480861f31c18ecb5uL),
            V(219, 0x15bc035d5c935a38uL, 0x0d37935a999ab5cauL, 0x6542afe01c5aeb9auL),
            V(220, 0x32ad682a5c21e221uL, 0x3d6686302ff56583uL, 0xcbd06527fe8e6589uL),
            V(221, 0x34f6d89ca95a5951uL, 0x5c55a0ead97a1081uL, 0xbecd0c36bbcaf2d0uL),
            V(222, 0xf2e9c528474bb124uL, 0x3f76898999bdb6fcuL, 0x2393dc6605570fc8uL),
            V(223, 0x83fdf3f5c3b163b4uL, 0xbd8e8b2dd45aaf89uL, 0xadc9798af4e8dabcuL),
            V(224, 0x64d49ce89e01c70buL, 0x6f2d6f4dc76e6bb9uL, 0x640609ea79c8ad36uL),
            V(225, 0xafdda8edb3158744uL, 0x3d683b8ff6774343uL, 0xf0891fce5e0e0c8auL),
            V(226, 0x5b27d1b0c140020cuL, 0x9961e9c5c22846e7uL, 0x2edc4fbb099df8c8uL),
            V(227, 0x2f5d6e7c3f21b3d3uL, 0x2288e7e56762f560uL, 0x2b5b3e50558ccd10uL),
            V(228, 0x49dee81998e5131buL, 0x2305b3e46dd6d46euL, 0x2e4e986bd346c048uL),
            V(229, 0x8b1ce2cd705533d1uL, 0x8d9b55a4d4da199euL, 0xb5bcbe3a25fb9c2cuL),
            V(230, 0xd163a876b162c492uL, 0xb689cce10096207euL, 0x51e48742371c27d5uL),
            V(231, 0x74b8c90e60b92149uL, 0x301127cfca6944b1uL, 0xe6eeeb2505ced10fuL),
            V(232, 0x81b1962ecef4f101uL, 0x9b0d2b16043dcf03uL, 0xd64efbcc30b7af29uL),
            V(233, 0x3661bd1a56843670uL, 0x27e0f4701e234445uL, 0x1951630af3c7ead6uL),
            V(234, 0xd88ac08d483d68beuL, 0xe0ab2f612901aed0uL, 0xdae61919b25fe91auL),
            V(235, 0x3369bb0402aef6fcuL, 0x88b851a2062b1b30uL, 0x941df25f1a1f2744uL),
            V(236, 0xe55ae9fe4f1ddaabuL, 0xe2decd12a1318cd2uL, 0xa4bd99fb0f1b1f13uL),
            V(237, 0x08229028cb6f574fuL, 0x27bbcbe1b399d1a8uL, 0xa46a38899efff39duL),
            V(238, 0xfa5d17f599eb8cd8uL, 0xaea25fa14875c6a1uL, 0xbc8b6520c0ea6680uL),
            V(239, 0x808926d0a5948ceauL, 0x746ad86266020ba8uL, 0x20f167e97d39869buL),
            V(240, 0x482651c032f75ac1uL, 0x6a0fcfbce2ecd333uL, 0x4da0fd11cc2d182fuL),
            V(241, 0xadb0a4072a744d0euL, 0x048722c133848b3auL, 0x048722c133848b3auL),
            V(242, 0x7ddcdfd1b5ab0c8fuL, 0xb120ac4e12ad8c37uL, 0xb120ac4e12ad8c37uL),
            V(243, 0xb6b3bbefd3645ea3uL, 0x15e09f6e08a14c7cuL, 0x15e09f6e08a14c7cuL),
            V(244, 0xfe793c7c3aac0383uL, 0x66c46e7f9de33b0buL, 0x66c46e7f9de33b0buL),
            V(245, 0xbad10d90cfa578f9uL, 0xd98b4d64105d33b0uL, 0xd98b4d64105d33b0uL),
            V(246, 0xb77962f51a3658e4uL, 0x8c3118edc210e8b2uL, 0x8c3118edc210e8b2uL),
            V(247, 0x244016f16bcf3cb8uL, 0xd7bd9429d44cf073uL, 0xd7bd9429d44cf073uL),
            V(248, 0x79116b48b37949f2uL, 0x46b549ff8d0b4604uL, 0x46b549ff8d0b4604uL),
            V(249, 0x66734a6e9fe5d3a6uL, 0xd2e200c4757d922cuL, 0xd2e200c4757d922cuL),
            V(250, 0xbbf7c74a7a52a2cauL, 0x3b28ed7803bac8d0uL, 0x3b28ed7803bac8d0uL),
            V(251, 0xee8fa27affa77a21uL, 0x04cf3aca8ec021e4uL, 0x04cf3aca8ec021e4uL),
            V(252, 0xb4c3304da23659dcuL, 0xc0993ba4116bddb3uL, 0xc0993ba4116bddb3uL),
            V(253, 0x13fc1075273bc7d1uL, 0x43be1461bc1c92b3uL, 0x43be1461bc1c92b3uL),
            V(254, 0x89a506558e1f1fa9uL, 0x36e4026b17875584uL, 0x36e4026b17875584uL),
            V(255, 0x23234db8219f93f2uL, 0xa115087f3c289b26uL, 0xa115087f3c289b26uL),
            V(256, 0x27e2566b0d81082buL, 0x018f8d3d9593f704uL, 0x018f8d3d9593f704uL),
            V(257, 0xfb2cec0c34971195uL, 0xd11f273b318e0789uL, 0xd11f273b318e0789uL),
            V(258, 0x474fd1da5904ca3duL, 0xc27e6c37a00d95f5uL, 0xc27e6c37a00d95f5uL),
            V(259, 0x1bf2f6a91214e505uL, 0xf515e9c029a8bd82uL, 0xf515e9c029a8bd82uL),
            V(260, 0x526cb28609999cb6uL, 0xf960b4ee2eaa2782uL, 0xf960b4ee2eaa2782uL),
            V(261, 0xc14f8e03b849568buL, 0x4c3185597b2b5068uL, 0x4c3185597b2b5068uL),
            V(262, 0x2752f19d5e3f075auL, 0xcfbf349a4d792743uL, 0xcfbf349a4d792743uL),
            V(263, 0x8db445467ee3cfa0uL, 0xc48c48ba82f91bdfuL, 0xc48c48ba82f91bdfuL),
            V(264, 0x9c3fa37e918bd714uL, 0x566537868e197e6fuL, 0x566537868e197e6fuL),
            V(265, 0xe4cdd332393dbc24uL, 0x111edf31d8ab799buL, 0x111edf31d8ab799buL),
            V(266, 0x6028549bbd186290uL, 0xabede9c76c2cb359uL, 0xabede9c76c2cb359uL),
            V(267, 0x5e1dba489d4a7d46uL, 0x6dbd5fd26ee9f216uL, 0x6dbd5fd26ee9f216uL),
            V(268, 0x9643c873fa949d41uL, 0x8c165b25f54b8267uL, 0x8c165b25f54b8267uL),
            V(269, 0x5d9f32044d3bc340uL, 0x8ad4c47b59a944ccuL, 0x8ad4c47b59a944ccuL),
            V(270, 0x17e10267f81b97a5uL, 0xcbe9a125ce5ae5c9uL, 0xcbe9a125ce5ae5c9uL),
            V(271, 0x5208d1a2b1c00e16uL, 0xe11c5b0afc28660euL, 0xe11c5b0afc28660euL),
            V(272, 0x4d3c9977c0c6fa14uL, 0xd67bcc2eddfd9251uL, 0xd67bcc2eddfd9251uL),
            V(273, 0x52fa820f4dc57b18uL, 0x7178d35b2dc1e4ffuL, 0x7178d35b2dc1e4ffuL),
            V(274, 0xc298be22c10f5d05uL, 0x6e6550ce9b80bda2uL, 0x6e6550ce9b80bda2uL),
            V(275, 0x3130c0cd216120cduL, 0xc253d15dd3ec19e5uL, 0xc253d15dd3ec19e5uL),
            V(276, 0x58a07c1c4023106cuL, 0x86c72425e2c57713uL, 0x86c72425e2c57713uL),
            V(277, 0x88a559313808bf3cuL, 0x237b8c2bd7c7d5e6uL, 0x237b8c2bd7c7d5e6uL),
            V(278, 0x3b4d40695fd05acbuL, 0x1c6f2db5006d8e71uL, 0x1c6f2db5006d8e71uL),
            V(279, 0x73d7c0726a1d4d94uL, 0x78448eebd1df72a5uL, 0x78448eebd1df72a5uL),
            V(280, 0x6dcf795c03fd5bbauL, 0x9c6e23477c070bdbuL, 0x9c6e23477c070bdbuL),
            V(281, 0x8d24dc0d73c36b4duL, 0x2016b09838d6eb87uL, 0x2016b09838d6eb87uL),
            V(282, 0x387b82fb653fa59euL, 0xc881ac2e91ee29cfuL, 0xc881ac2e91ee29cfuL),
            V(283, 0x6709933cd024fb10uL, 0x40d818f756aee9f5uL, 0x40d818f756aee9f5uL),
            V(284, 0xa3ca11a803e77c5duL, 0x97f6cca4a520e978uL, 0x97f6cca4a520e978uL),
            V(285, 0x74738d5d17a45d59uL, 0x3abf2e9138d85c2euL, 0x3abf2e9138d85c2euL),
            V(286, 0xcec45625e3311e4euL, 0x96a6efbd21d04740uL, 0x96a6efbd21d04740uL),
            V(287, 0xf745139099be31b7uL, 0xcb6763836ad42568uL, 0xcb6763836ad42568uL),
            V(288, 0x0cf1cfb0b3dbd33buL, 0x43144d33ac5d7a12uL, 0x43144d33ac5d7a12uL),
            V(289, 0xcb2555dd465e8f5duL, 0x1cfad37d43be73f2uL, 0x1cfad37d43be73f2uL),
            V(290, 0x564ba575b34485d9uL, 0x625641219304cbc0uL, 0x625641219304cbc0uL),
            V(291, 0xdf22c409106768bfuL, 0x098620ac7328692buL, 0x098620ac7328692buL),
            V(292, 0x8fad35fdecea2d4euL, 0xb6f626c1f17ca947uL, 0xb6f626c1f17ca947uL),
            V(293, 0x15ada15c4277329fuL, 0xac9ed3dda1ab345buL, 0xac9ed3dda1ab345buL),
            V(294, 0x5ded455559e1d0f1uL, 0xbfa1ba19c4d74e94uL, 0xbfa1ba19c4d74e94uL),
            V(295, 0x1be09bea872e8e49uL, 0x7ae26aeea0b2639buL, 0x7ae26aeea0b2639buL),
            V(296, 0x7a9787378725b4c7uL, 0x66c1bf2b8a9dcf0fuL, 0x66c1bf2b8a9dcf0fuL),
            V(297, 0xb1ad0c258fb2ff7cuL, 0xdcb924b2f79ed229uL, 0xdcb924b2f79ed229uL),
            V(298, 0x463740b36d6e8a39uL, 0x4481dc0a8efe177duL, 0x4481dc0a8efe177duL),
            V(299, 0x77aebcc7712adb59uL, 0x3b5fa6b02f5ef2aduL, 0x3b5fa6b02f5ef2aduL),
            V(300, 0x9f75b1c97476db48uL, 0x8f291612a0319f63uL, 0x8f291612a0319f63uL),
            V(511, 0x4b9e02b94a84f50buL, 0xd732de33b0ac8995uL, 0xd732de33b0ac8995uL),
            V(512, 0x624fafaba841a2ceuL, 0x655786f73d368b83uL, 0x655786f73d368b83uL),
            V(513, 0x4d43038ac10bd7f8uL, 0xe6878cf184ba3452uL, 0xe6878cf184ba3452uL),
            V(1023, 0x4c7460833a3db4bfuL, 0x2af4cccc9c502b44uL, 0x2af4cccc9c502b44uL),
            V(1024, 0xd5e6b158089dc792uL, 0x3791b2ffd6159cc7uL, 0x3791b2ffd6159cc7uL),
            V(1025, 0xe306a98411aca4c5uL, 0x836bdaec2bee9821uL, 0x836bdaec2bee9821uL),
            V(1088, 0x4733071fff3707f5uL, 0xafbfac231dfb0e3buL, 0xafbfac231dfb0e3buL),
            V(2047, 0x37d9bacb0a84f6d5uL, 0x82d6f72a7265a2bbuL, 0x82d6f72a7265a2bbuL),
            V(2048, 0xbb90d3c4d14924ceuL, 0xbf9b317ad8f3bdeeuL, 0xbf9b317ad8f3bdeeuL),
            V(2049, 0x32e4d9fa47292cc2uL, 0x21da220a7434562auL, 0x21da220a7434562auL),
            V(3000, 0x3964789fb99025f7uL, 0xdfdca68fc77ddaf6uL, 0xdfdca68fc77ddaf6uL),
            V(4096, 0xa377742091931986uL, 0xd032716b37852b9cuL, 0xd032716b37852b9cuL),
            V(4097, 0x93f32651cf1fa2d9uL, 0x6f9a7cbf175c933euL, 0x6f9a7cbf175c933euL),
            V(65536, 0xc69423904b4a7350uL, 0x31e070f85e1a0787uL, 0x31e070f85e1a0787uL),
            V(65537, 0x8b430892f332d7fcuL, 0x3726f0e2d90f6b16uL, 0x3726f0e2d90f6b16uL),
            V(1048576, 0x192db93b008e728duL, 0xcf4baad082654b84uL, 0xcf4baad082654b84uL),
        )

        val TEXTS: List<S> = listOf(
            S("?", 0x13941ad4ddb2863fuL, 0x8b72305f19fe690buL, 0x8b72305f19fe690buL),
            S("-", 0xeb7df54fc0abac3fuL, 0xde2fb8d1c1a20f76uL, 0xde2fb8d1c1a20f76uL),
            S("*", 0x14c9ae9594c463c4uL, 0x79d03016b7aeed0duL, 0x79d03016b7aeed0duL),
            S("#", 0x3fa0d17948f5c24fuL, 0x6a81ae5441d08140uL, 0x6a81ae5441d08140uL),
            S("<", 0xbeff62be44bc9be4uL, 0x429e81bc6744101cuL, 0x429e81bc6744101cuL),
            S(">", 0x275810d2c0a53838uL, 0x5e03d77c392f4884uL, 0x5e03d77c392f4884uL),
            S("hello", 0xb5e9c1ad071b3e7fuL, 0xc779cfaa5e523818uL, 0x9555e8555c62dcfduL),
            S("héllo wörld — 日本語", 0xbe6414508d13d588uL, 0xa78ddc81de949b68uL, 0x0ae756d6cd6cba7fuL),
            S("const x: number = 1;\n", 0xeefaf02de3b3672euL, 0x7655348ddb779298uL, 0xf211c766a558daf1uL),
        )
    }
}
