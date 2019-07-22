import groovy.transform.Field

// Keep track of builds that fail
@Field failed_builds = [:]

//Record coverage details for reporting
@Field coverage_details = ""

def gen_simple_windows_jobs(label, script) {
    def jobs = [:]

    jobs[label] = {
        node("windows-tls") {
            deleteDir()
            checkout_repo.checkout_repo()
            timeout(time: common.perJobTimeout.time,
                    unit: common.perJobTimeout.unit) {
                bat script
            }
        }
    }
    return jobs
}

def gen_docker_jobs_foreach(label, platforms, compilers, script) {
    def jobs = [:]

    for (platform in platforms) {
        for (compiler in compilers) {
            def job_name = "${label}-${compiler}-${platform}"
            def shell_script = sprintf(script, common.compiler_paths[compiler])
            jobs[job_name] = {
                node("mbedtls && ubuntu-16.10-x64") {
                    timestamps {
                        deleteDir()
                        common.get_docker_image(platform)
                        dir('src') {
                            checkout_repo.checkout_repo()
                            writeFile file: 'steps.sh', text: """\
#!/bin/sh
set -ux
ulimit -f 20971520
${shell_script}
"""
                        }
                        timeout(time: common.perJobTimeout.time,
                                unit: common.perJobTimeout.unit) {
                            sh """\
chmod +x src/steps.sh
docker run --rm -u \$(id -u):\$(id -g) --entrypoint /var/lib/build/steps.sh \
-w /var/lib/build -v `pwd`/src:/var/lib/build \
-v /home/ubuntu/.ssh:/home/mbedjenkins/.ssh $common.docker_repo:$platform
"""
                        }
                    }
                }
            }
        }
    }
    return jobs
}

def gen_node_jobs_foreach(label, platforms, compilers, script) {
    def jobs = [:]

    for (platform in platforms) {
        for (compiler in compilers) {
            def job_name = "${label}-${compiler}-${platform}"
            def shell_script = sprintf(script, common.compiler_paths[compiler])
            jobs[job_name] = {
                node(platform) {
                    timestamps {
                        deleteDir()
                        checkout_repo.checkout_repo()
                        if (label == 'coverity') {
                            checkout_repo.checkout_coverity_repo()
                        }
                        shell_script = """
ulimit -f 20971520
export PYTHON=/usr/local/bin/python2.7
""" + shell_script
                        timeout(time: common.perJobTimeout.time,
                                unit: common.perJobTimeout.unit) {
                            sh shell_script
                        }
                    }
                }
            }
        }
    }
    return jobs
}

def gen_all_sh_jobs(platform, component) {
    def jobs = [:]

    jobs["all_sh-${platform}-${component}"] = {
        node('ubuntu-16.10-x64 && mbedtls') {
            try {
                timestamps {
                    deleteDir()
                    common.get_docker_image(platform)
                    dir('src') {
                        checkout_repo.checkout_repo()
                        writeFile file: 'steps.sh', text: """\
#!/bin/sh
set -ux
ulimit -f 20971520
git config --global user.email "you@example.com"
git config --global user.name "Your Name"
git init
git add .
git commit -m 'CI code copy'
export LOG_FAILURE_ON_STDOUT=1
set ./tests/scripts/all.sh --seed 4 --keep-going $component
"\$@"
"""
                    }
                    timeout(time: common.perJobTimeout.time,
                            unit: common.perJobTimeout.unit) {
                        sh """\
chmod +x src/steps.sh
docker run -u \$(id -u):\$(id -g) --rm --entrypoint /var/lib/build/steps.sh \
-w /var/lib/build -v `pwd`/src:/var/lib/build \
-v /home/ubuntu/.ssh:/home/mbedjenkins/.ssh \
--cap-add SYS_PTRACE $common.docker_repo:$platform
"""
                    }
                }
            } catch (err) {
                failed_builds["all.sh-${component}"] = true
                throw (err)
            }
        }
    }
    return jobs
}

def gen_windows_tests_jobs(build) {
    def jobs = [:]

    jobs["Windows-${build}"] = {
        node("windows-tls") {
            try {
                dir("src") {
                    deleteDir()
                    checkout_repo.checkout_repo()
                }
                /* The empty files are created to re-create the directory after it
                 * and its contents have been removed by deleteDir. */
                dir("logs") {
                    deleteDir()
                    writeFile file:'_do_not_delete_this_directory.txt', text:''
                }

                dir("worktrees") {
                    deleteDir()
                    writeFile file:'_do_not_delete_this_directory.txt', text:''
                }
                /* libraryResource loads the file as a string. This is then
                 * written to a file so that it can be run on a node. */
                def windows_testing = libraryResource 'windows/windows_testing.py'
                writeFile file: 'windows_testing.py', text: windows_testing
                timeout(time: common.perJobTimeout.time,
                        unit: common.perJobTimeout.unit) {
                    bat "python windows_testing.py src logs $env.BRANCH_NAME -b $build"
                }
            } catch (err) {
                failed_builds["Windows ${build} tests"] = true
                throw (err)
            }
        }
    }
    return jobs
}

def gen_abi_api_checking_job(platform) {
    def jobs = [:]

    jobs["ABI/API checking"] = {
        node('ubuntu-16.10-x64 && mbedtls') {
            timestamps {
                deleteDir()
                common.get_docker_image(platform)
                dir('src') {
                    checkout_repo.checkout_repo()
                    sh(
                        returnStdout: true,
                        script: "git fetch origin ${CHANGE_TARGET}"
                    ).trim()
                    writeFile file: 'steps.sh', text: """\
#!/bin/sh
set -ux
ulimit -f 20971520
tests/scripts/list-identifiers.sh --internal
scripts/abi_check.py -o FETCH_HEAD -n HEAD -s identifiers --brief
"""
                }
                timeout(time: common.perJobTimeout.time,
                        unit: common.perJobTimeout.unit) {
                    sh """\
chmod +x src/steps.sh
docker run --rm -u \$(id -u):\$(id -g) --entrypoint /var/lib/build/steps.sh \
-w /var/lib/build -v `pwd`/src:/var/lib/build \
-v /home/ubuntu/.ssh:/home/mbedjenkins/.ssh $common.docker_repo:$platform
"""
                }
            }
        }
    }
    return jobs
}

def gen_code_coverage_job(platform) {
    def jobs = [:]

    jobs['code_coverage'] = {
        node('mbedtls && ubuntu-16.10-x64') {
            try {
                deleteDir()
                common.get_docker_image(platform)
                dir('src') {
                    checkout_repo.checkout_repo()
                    writeFile file: 'steps.sh', text: '''#!/bin/sh
set -ux
ulimit -f 20971520
./tests/scripts/basic-build-test.sh 2>&1
'''
                }
                timeout(time: common.perJobTimeout.time,
                        unit: common.perJobTimeout.unit) {
                    coverage_log = sh returnStdout: true, script: """
chmod +x src/steps.sh
docker run -u \$(id -u):\$(id -g) --rm --entrypoint /var/lib/build/steps.sh \
-w /var/lib/build -v `pwd`/src:/var/lib/build \
-v /home/ubuntu/.ssh:/home/mbedjenkins/.ssh $common.docker_repo:$platform
"""
                }
            } catch (err) {
                failed_builds['basic-build-test'] = true
                throw (err)
            } finally {
                echo coverage_log
                coverage_details = coverage_log.substring(
                    coverage_log.indexOf('Test Report Summary')
                )
                coverage_details = coverage_details.substring(
                    coverage_details.indexOf('Coverage')
                )
            }
        }
    }
    return jobs
}

def gen_mbed_os_example_job(repo, branch, example, compiler, platform) {
    def jobs = [:]

    jobs["${example}-${platform}-${compiler}"] = {
        node(compiler) {
            try {
                def use_psa_crypto = ""
                if (env.TARGET_REPO == 'crypto' &&
                    common.platforms_with_entropy_sources.contains(platform)) {
                    use_psa_crypto = "-DMBEDTLS_PSA_CRYPTO_C"
                }
                timestamps {
                    deleteDir()
                    checkout_repo.checkout_parametrized_repo(repo, branch)
                    dir(example) {
/* This script appears to do nothing, however it is needed in a few cases.
 * We wish to deploy specific versions of Mbed OS, TLS and Crypto, so we
 * remove mbed-os.lib to not deploy it twice. Mbed deploy is still needed in
 * case other libraries exist to be deployed. */
                        sh """\
ulimit -f 20971520
rm -f mbed-os.lib
mbed config root .
mbed deploy -vv
"""
                        dir('mbed-os') {
                            deleteDir()
                            checkout_repo.checkout_mbed_os()
                        }
                        timeout(time: common.perJobTimeout.time +
                                      common.perJobTimeout.raasOffset,
                                unit: common.perJobTimeout.unit) {
                            def tag_filter = ""
                            if (example == 'atecc608a') {
                                sh './update-crypto.sh'
                                tag_filter = "--tag-filters HAS_CRYPTOKIT"
                            }
                            sh """\
ulimit -f 20971520
mbed compile -m ${platform} -t ${compiler} ${use_psa_crypto}
"""
                            def attempts = 0
                            while (true) {
                                try {
                                    sh """\
ulimit -f 20971520
if [ -e BUILD/${platform}/${compiler}/${example}.bin ]
then
    BINARY=BUILD/${platform}/${compiler}/${example}.bin
else
    if [ -e BUILD/${platform}/${compiler}/${example}.hex ]
    then
        BINARY=BUILD/${platform}/${compiler}/${example}.hex
    fi
fi

export RAAS_PYCLIENT_FORCE_REMOTE_ALLOCATION=1
export RAAS_PYCLIENT_ALLOCATION_QUEUE_TIMEOUT=3600
mbedhtrun -m ${platform} ${tag_filter} \
-g raas_client:https://auli.mbedcloudtesting.com:443 -P 1000 --sync=0 -v \
--compare-log ../tests/${example}.log -f \$BINARY
"""
                                break
                                } catch (err) {
                                    attempts += 1
                                    if (attempts >= 3) {
                                        throw (err)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (err) {
                failed_builds["${example}-${platform}-${compiler}"] = true
                throw (err)
            }
        }
    }
    return jobs
}

def gen_iar_windows_job() {
    def jobs = [:]

    jobs['iar8-mingw'] = {
        node("windows-tls") {
            try {
                dir("src") {
                    deleteDir()
                    checkout_repo.checkout_repo()
                    timeout(time: common.perJobTimeout.time,
                            unit: common.perJobTimeout.unit) {
                        bat """
perl scripts/config.pl baremetal
cmake -D CMAKE_BUILD_TYPE:String=Check -DCMAKE_C_COMPILER="iccarm" \
-G "MinGW Makefiles" .
mingw32-make lib
"""
                    }
                }
            } catch (err) {
                failed_builds['iar8-mingw'] = true
                throw (err)
            }
        }
    }
    return jobs
}
