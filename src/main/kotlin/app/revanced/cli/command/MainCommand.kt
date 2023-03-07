package app.revanced.cli.command

import app.revanced.cli.logging.impl.DefaultCliLogger
import app.revanced.cli.patcher.logging.impl.PatcherLogger
import app.revanced.cli.signing.SigningOptions
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.apk.Apk
import app.revanced.patcher.apk.ApkBundle
import app.revanced.patcher.extensions.PatchExtensions.compatiblePackages
import app.revanced.patcher.extensions.PatchExtensions.description
import app.revanced.patcher.extensions.PatchExtensions.include
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.util.patch.PatchBundle
import app.revanced.utils.OptionsLoader
import app.revanced.utils.adb.Adb
import app.revanced.utils.apk.ApkSigner
import app.revanced.utils.zip.Alignment
import app.revanced.utils.zip.ZipFile
import app.revanced.utils.zip.structures.ZipEntry
import picocli.CommandLine.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private class CLIVersionProvider : IVersionProvider {
    override fun getVersion() = arrayOf(
        MainCommand::class.java.`package`.implementationVersion ?: "unknown"
    )
}

@Command(
    name = "ReVanced-CLI",
    mixinStandardHelpOptions = true,
    versionProvider = CLIVersionProvider::class
)
internal object MainCommand : Runnable {
    val logger = DefaultCliLogger()

    @ArgGroup(exclusive = false, multiplicity = "1")
    lateinit var args: Args

    /**
     * Arguments for the CLI
     */
    class Args {
        @Option(names = ["--uninstall"], description = ["Uninstall the mounted apk by its package name"])
        var uninstall: String? = null

        @Option(names = ["-d", "--deploy-on"], description = ["If specified, deploy to adb device with given name"])
        var deploy: String? = null

        @Option(names = ["--mount"], description = ["If specified, instead of installing, mount"])
        var mount: Boolean = false

        @ArgGroup(exclusive = false)
        var patchArgs: PatchArgs? = null

        /**
         * Arguments for patches.
         */
        class PatchArgs {
            @Option(names = ["-b", "--bundle"], description = ["One or more bundles of patches"], required = true)
            var patchBundles = arrayOf<String>()

            @ArgGroup(exclusive = false)
            var listingArgs: ListingArgs? = null

            @ArgGroup(exclusive = false)
            var patchingArgs: PatchingArgs? = null

            /**
             * Arguments for patching.
             */
            class PatchingArgs {
                @ArgGroup(exclusive = false, multiplicity = "1")
                val apkArgs: ApkArgs? = null

                @Option(names = ["-o", "--out"], description = ["Output folder path"], required = true)
                var outputPath: File = File("revanced")

                @Option(names = ["--options"], description = ["Configuration file for all patch options"])
                var options: File = outputPath.resolve("options.toml")

                @Option(names = ["-e", "--exclude"], description = ["Explicitly exclude patches"])
                var excludedPatches = arrayOf<String>()

                @Option(
                    names = ["--exclusive"],
                    description = ["Only installs the patches you include, excluding patches by default"]
                )
                var exclusive = false

                @Option(names = ["-i", "--include"], description = ["Include patches"])
                var includedPatches = arrayOf<String>()

                @Option(names = ["--experimental"], description = ["Disable patch version compatibility patch"])
                var experimental: Boolean = false

                @Option(names = ["-m", "--merge"], description = ["One or more dex file containers to merge"])
                var mergeFiles = listOf<File>()

                @Option(names = ["--cn"], description = ["Overwrite the default CN for the signed file"])
                var cn = "ReVanced"

                @Option(names = ["--keystore"], description = ["File path to your keystore"])
                var keystorePath: String? = null

                @Option(
                    names = ["-p", "--password"],
                    description = ["Overwrite the default password for the signed file"]
                )
                var password = "ReVanced"

                @Option(names = ["-t", "--temp-dir"], description = ["Temporary work directory"])
                var workDirectory = File("revanced-cache")

                @Option(
                    names = ["-c", "--clean"],
                    description = ["Clean the temporary work directory. This will always be done before running the patcher"]
                )
                var clean: Boolean = false

                @Option(names = ["--custom-aapt2-binary"], description = ["Path to custom aapt2 binary"])
                var aaptPath: String = ""

                /**
                 * Arguments for [Apk] files.
                 */
                class ApkArgs {
                    @Option(
                        names = ["-a", "--base-apk"],
                        description = ["The base apk file that is to be patched"],
                        required = true
                    )
                    lateinit var baseApk: String

                    @ArgGroup(exclusive = false)
                    val splitsArgs: SplitsArgs? = null

                    class SplitsArgs {
                        @Option(
                            names = ["--language-apk"],
                            description = ["Additional split apk file which contains language files"], required = true
                        )
                        lateinit var languageApk: String

                        @Option(
                            names = ["--library-apk"],
                            description = ["Additional split apk file which contains libraries"], required = true
                        )
                        lateinit var libraryApk: String

                        @Option(
                            names = ["--asset-apk"],
                            description = ["Additional split apk file which contains assets"], required = true
                        )
                        lateinit var assetApk: String
                    }
                }
            }

            /**
             * Arguments for printing patches.
             */
            class ListingArgs {
                @Option(names = ["-l", "--list"], description = ["List patches only"], required = true)
                var listOnly: Boolean = false

                @Option(names = ["--with-versions"], description = ["List patches with compatible versions"])
                var withVersions: Boolean = false

                @Option(names = ["--with-packages"], description = ["List patches with compatible packages"])
                var withPackages: Boolean = false
            }
        }
    }

    override fun run() {
        // other types of commands
        // TODO: convert this code to picocli subcommands
        if (args.patchArgs?.listingArgs?.listOnly == true) return printListOfPatches()
        if (args.uninstall != null) return uninstall()

        // patching commands require these arguments
        val patchArgs = this.args.patchArgs ?: return
        val patchingArgs = patchArgs.patchingArgs ?: return

        // prepare the work directory, delete it if it already exists
        val workDirectory = patchingArgs.workDirectory.also {
            if (!it.deleteRecursively())
                return logger.error("Failed to delete work directory")
        }

        // prepare apks
        val apkArgs = patchingArgs.apkArgs!!

        val baseApk = Apk.Base(apkArgs.baseApk)
        val splitApk = apkArgs.splitsArgs?.let { args ->
            with(args) {
                ApkBundle.Split(
                    Apk.Split.Library(libraryApk),
                    Apk.Split.Asset(assetApk),
                    Apk.Split.Language(languageApk)
                )
            }
        }

        // prepare the patches
        val allPatches = patchArgs.patchBundles.flatMap { bundle -> PatchBundle.Jar(bundle).loadPatches() }.also {
            OptionsLoader.init(patchingArgs.options, it)
        }

        // prepare the patcher
        val patcher = Patcher( // constructor decodes base
            PatcherOptions(
                ApkBundle(baseApk, splitApk),
                workDirectory.path,
                patchingArgs.aaptPath,
                workDirectory.path,
                PatcherLogger
            )
        )

        // prepare adb
        val adb: Adb? = args.deploy?.let { device ->
            if (args.mount) {
                Adb.RootAdb(device, logger)
            } else {
                Adb.UserAdb(device, logger)
            }
        }

        with(workDirectory.resolve("cli")) {
            val alignedDirectory = resolve("aligned").also(File::mkdirs)
            val signedDirectory = resolve("signed").also(File::mkdirs)

            /**
             * Align an [Apk] file.
             *
             * @param apk The apk file to write.
             * @return The written [Apk] file.
             */
            fun writeApk(apk: Apk): File {
                logger.info("Writing $apk apk")

                with(apk) {
                    return alignedDirectory.resolve(file.name).also { alignedApk ->
                        if (alignedApk.exists()) alignedApk.delete()

                        ZipFile(alignedApk).use { zipFile ->
                            if (this is Apk.Base) {
                                logger.info("Writing dex files")
                                dexFiles.forEach {
                                    zipFile.addEntryCompressData(
                                        ZipEntry.createWithName(
                                            it.name
                                        ),
                                        it.stream.readBytes()
                                    )
                                }
                                /**
                                 * Copy a file to [ZipFile].
                                 * If it is a directory, the contents including the directory will be copied as well.
                                 *
                                 * @param file The file to copy to the [ZipFile].
                                 */
                                fun ZipFile.deepCopy(file: File) {
                                    val base = file.parentFile

                                    fun File.recursivelyCopy() {
                                        if (isDirectory) listFiles()?.forEach(File::recursivelyCopy)
                                        else addFileCompressedAligned(
                                            this,
                                            this.relativeTo(base).path,
                                            Alignment::getEntryAlignment
                                        )
                                    }

                                    file.recursivelyCopy()
                                }
                                mergedResources?.forEach {
                                    zipFile.deepCopy(it)
                                }
                            }


                            resources?.let {
                                logger.info("Writing resources")

                                zipFile.copyEntriesFromFileAligned(
                                    ZipFile(it),
                                    Alignment::getEntryAlignment
                                )
                            }

                            logger.info("Writing remaining files")

                            zipFile.copyEntriesFromFileAligned(
                                ZipFile(this.file),
                                Alignment::getEntryAlignment
                            )
                        }
                    }
                }
            }

            /**
             * Sign a list of [Apk] files.
             *
             * @param unsignedApks The list of [Apk] files to sign.
             * @return The list of signed [Apk] files.
             */
            fun signApks(unsignedApks: List<File>) = if (!args.mount) {
                with(
                    ApkSigner(
                        SigningOptions(
                            patchingArgs.cn,
                            patchingArgs.password,
                            patchingArgs.keystorePath
                                ?: patchingArgs.outputPath.absoluteFile.resolve("${baseApk.file.nameWithoutExtension}.keystore").canonicalPath
                        )
                    )
                ) {
                    unsignedApks.map { unsignedApk -> // sign the unsigned apk
                        logger.info("Signing ${unsignedApk.name}")
                        signedDirectory.resolve(unsignedApk.name)
                            .also { signedApk ->
                                signApk(
                                    unsignedApk, signedApk
                                )
                            }
                    }
                }
            } else {
                unsignedApks
            }

            /**
             * Move an [Apk] file to the output directory.
             *
             * @param apk The [Apk] file to copy.
             * @return The moved [Apk] file.
             */
            fun moveToOutput(apk: File) = patchingArgs.outputPath.resolve(apk.name).also {
                logger.info("Moving ${apk.name} to ${it.absolutePath}")

                Files.move(apk.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }


            /**
             * Install an [Apk] file to the device.
             *
             * @param apkFiles The [Apk] files to install.
             * @return The input [Apk] file.
             */
            fun install(apkFiles: List<Pair<File, Apk>> /* pair of the apk file and the apk */) =
                apkFiles.also {
                    adb?.apply {
                        fun Pair<File, Apk>.intoAdbApk() = Adb.Apk(this.first.path)

                        val base = it.find { (_, apk) -> apk is Apk.Base }!!.let(Pair<File, Apk>::intoAdbApk)
                        val splits = it.filter { (_, apk) -> apk is Apk.Split }.map(Pair<File, Apk>::intoAdbApk)

                        install(base, splits)
                    }
                }.map { (outputApk, _) -> outputApk }

            /**
             * Clean up the work directory and output files.
             *
             * @param outputApks The list of output [Apk] files.
             */
            fun cleanUp(outputApks: List<File>) {
                // clean up the work directory if needed
                if (patchingArgs.clean) {
                    patchingArgs.workDirectory.let {
                        if (!it.deleteRecursively())
                            return logger.error("Failed to delete directory $it")
                    }
                    if (args.deploy?.let { outputApks.any { !it.delete() } } == true)
                        logger.error("Failed to delete some output files")
                }
            }

            /**
             * Run the patcher and save the patched resources.
             *
             * @return The resulting patched [Apk] files.
             */
            fun Patcher.run() = also {
                addIntegrations(patchingArgs.mergeFiles)

                val packageName = baseApk.packageMetadata.packageName
                val packageVersion = baseApk.packageMetadata.packageVersion

                sequence {
                    allPatches.forEach patch@{ patch ->
                        val patchName = patch.patchName

                        val prefix = "Skipping $patchName"

                        /**
                         * Check if the patch is explicitly excluded.
                         *
                         * Cases:
                         *  1. -e patch.name
                         *  2. -i patch.name -e patch.name
                         */

                        val excluded = patchingArgs.excludedPatches.contains(patchName)
                        if (excluded) return@patch logger.info("$prefix: Explicitly excluded")

                        /**
                         * Check if the patch is constrained to packages.
                         */

                        patch.compatiblePackages?.let { packages ->
                            packages.singleOrNull { it.name == packageName }?.let { `package` ->
                                /**
                                 * Check if the package version matches.
                                 * If experimental is true, version matching will be skipped.
                                 */

                                val matchesVersion = patchingArgs.experimental || `package`.versions.let {
                                    it.isEmpty() || it.any { version -> version == packageVersion }
                                }

                                if (!matchesVersion) return@patch logger.warn(
                                    "$prefix: Incompatible with version $packageVersion. " +
                                            "This patch is only compatible with version " +
                                            packages.joinToString(";") { `package` ->
                                                "${`package`.name}: ${`package`.versions.joinToString(", ")}"
                                            }
                                )

                            } ?: return@patch logger.trace(
                                "$prefix: Incompatible with $packageName. " +
                                        "This patch is only compatible with " +
                                        packages.joinToString(", ") { `package` -> `package`.name }
                            )

                            return@let
                        } ?: logger.trace("$patchName: No constraint on packages.")

                        /**
                         * Check if the patch is explicitly included.
                         *
                         * Cases:
                         *  1. --exclusive
                         *  2. --exclusive -i patch.name
                         */

                        val exclusive = patchingArgs.exclusive
                        val explicitlyIncluded = patchingArgs.includedPatches.contains(patchName)

                        val implicitlyIncluded = !exclusive && patch.include // Case 3.
                        val exclusivelyIncluded = exclusive && explicitlyIncluded // Case 2.

                        val included = implicitlyIncluded || exclusivelyIncluded
                        if (!included) return@patch logger.info("$prefix: Implicitly excluded") // Case 1.

                        logger.trace("Adding $patchName")
                        yield(patch)
                    }
                }.asIterable().let(this::addPatches)

                execute().forEach { (patch, result) ->
                    if (result is PatchResult.Error) logger.error("Executing $patch failed:\n${result.stackTraceToString()}")
                    else logger.info("Executing $patch succeeded")
                }
            }.save().apkFiles.map { it.apk }

            with(patcher.run()) {
                also { patchingArgs.outputPath.also(File::mkdirs) }
                    .map(::writeApk)
                    .let(::signApks)
                    .map(::moveToOutput).zip(this)
                    .let(::install)
                    .let(::cleanUp)
            }

            logger.info("Finished")
        }
    }

    private fun uninstall() {
        args.uninstall?.let { packageName ->
            args.deploy?.let { device ->
                Adb.UserAdb(device, logger).uninstall(packageName)
            } ?: return logger.error("You must specify a device to uninstall from")
        }
    }

    private fun printListOfPatches() {
        val logged = mutableListOf<String>()
        for (patchBundlePath in args.patchArgs?.patchBundles!!) for (patch in PatchBundle.Jar(patchBundlePath)
            .loadPatches()) {
            if (patch.patchName in logged) continue
            for (compatiblePackage in patch.compatiblePackages ?: continue) {
                val packageEntryStr = buildString {
                    // Add package if flag is set.
                    if (args.patchArgs?.listingArgs?.withPackages == true) {
                        val packageName = compatiblePackage.name.substringAfterLast(".").padStart(10)
                        append(packageName)
                        append("\t")
                    }
                    // Add patch name.
                    val patchName = patch.patchName.padStart(25)
                    append(patchName)
                    // Add compatible versions, if flag is set.
                    if (args.patchArgs?.listingArgs?.withVersions == true) {
                        val compatibleVersions = compatiblePackage.versions.joinToString(separator = ", ")
                        append("\t")
                        append(compatibleVersions)
                    }
                }

                logged.add(patch.patchName)
                logger.info(packageEntryStr)
            }
        }
    }
}
