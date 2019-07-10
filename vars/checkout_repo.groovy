def checkout_repo() {
    if (env.JOB_TYPE == 'PR') {
        checkout_pr()
    } else if (env.JOB_TYPE == 'release') {
        if (env.TARGET_REPO == 'crypto') {
            checkout_parametrized_repo(MBED_CRYPTO_REPO, MBED_CRYPTO_BRANCH)
        }
        if (env.TARGET_REPO == 'tls') {
            checkout_parametrized_repo(MBED_TLS_REPO, MBED_TLS_BRANCH)
        }
    }
}

/* If testing the TLS tests with an Mbed Crypto PR, checkout the Mbed TLS
 * development branch and update the crypto submodule to the Mbed Crypto PR branch
 * In all other cases, the standard scm checkout will checkout the PR branch
 * we wish to test. */
def checkout_pr() {
    if (env.TARGET_REPO == 'crypto' && env.REPO_TO_CHECKOUT == 'tls') {
        checkout([
            $class: 'GitSCM',
            branches: [[name: 'development']],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CloneOption',
                 timeout: 60],
                [$class: 'SubmoduleOption',
                 disableSubmodules: false,
                 parentCredentials: false,
                 recursiveSubmodules: true,
                 reference: '',
                 trackingSubmodules: false],
            ],
            submoduleCfg: [],
            userRemoteConfigs: [
                [credentialsId: env.GIT_CREDENTIALS_ID,
                 url: "git@github.com:ARMmbed/mbedtls.git"]
            ]
        ])
        dir('crypto') {
            checkout scm
        }
    } else {
        checkout scm
    }
}

def checkout_coverity_repo() {
    checkout([
        changelog: false,
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: '*/master']],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CloneOption',
                 noTags: true,
                 shallow: true],
                [$class: 'RelativeTargetDirectory',
                 relativeTargetDir: 'coverity-tools']
            ],
            submoduleCfg: [],
            userRemoteConfigs: [
                [url: 'git@github.com:ARMmbed/coverity-tools.git',
                 credentialsId: "${env.GIT_CREDENTIALS_ID}"]
            ]
        ]
    ])
}

def checkout_parametrized_repo(repo, branch) {
    checkout([
        scm: [
            $class: 'GitSCM',
            userRemoteConfigs: [
                [url: repo, credentialsId: env.GIT_CREDENTIALS_ID]
            ],
            branches: [[name: branch]],
            extensions: [
                [$class: 'CloneOption', timeout: 60],
                [$class: 'SubmoduleOption', recursiveSubmodules: true],
            ],
        ]
    ])
}
