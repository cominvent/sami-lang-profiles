# Profile Generator for language-identifier

This project was created to generate language identification profiles for the Sami languages
which would work with http://github.com/optimaize/language-detector

However I hope you can use the code to generate profiles for other languages as well.

## Usage

    mvn package
    java -jar target/profile-generator-0.1-SNAPSHOT.jar [-clean] <iso-code>

## Credits

For building the sami language profiles we had great help in the list of sources and URLs found at
[The Crúbadán Project](http://crubadan.org/). Please see their paper: [Corpus building for under-resourced languages](http://borel.slu.edu/pub/wac3.pdf)
for more information. The Sami resources used are licensed under the [cc-by](http://creativecommons.org/licenses/by/4.0/) license.