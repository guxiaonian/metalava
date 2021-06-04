# Metalava

Permission to API mapping relational database 

## Building and running

To build:
    
    $ git clone https://github.com/guxiaonian/metalava.git
    $ cd metalava
    $ ./gradlew

This builds a binary distribution in `../../out/host/common/install/metalava/bin/metalava`.

To run metalava:

    $ ../../out/host/common/install/metalava/bin/metalava
                    _        _
     _ __ ___   ___| |_ __ _| | __ ___   ____ _
    | '_ ` _ \ / _ \ __/ _` | |/ _` \ \ / / _` |
    | | | | | |  __/ || (_| | | (_| |\ V / (_| |
    |_| |_| |_|\___|\__\__,_|_|\__,_| \_/ \__,_|

    metalava extracts metadata from source code to generate artifacts such as the
    signature files, the SDK stub files, external annotations etc.

    Usage: metalava <flags>

    Flags:

    --help                                This message.
    --quiet                               Only include vital output
    --verbose                             Include extra diagnostic output

    ...

To Permission to API mapping relational database:

    $ ../../out/host/common/install/metalava/bin/metalava --source-files <file.java>
    $ ../../out/host/common/install/metalava/bin/metalava --source-path <folder>
    
To metalava.json

The metalava.json file is in the current directory
    
