/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.app.topics.lda
import cc.factorie._
import cc.factorie.generative._
import cc.factorie.app.strings.Stopwords
import scala.collection.mutable.HashMap
import java.io.{PrintWriter, FileWriter, File, BufferedReader, InputStreamReader, FileInputStream}
import collection.mutable.{ArrayBuffer, HashSet, HashMap}

class LDA(val wordSeqDomain: CategoricalSeqDomain[String], numTopics: Int = 10, alpha1:Double = 0.1, val beta1:Double = 0.1)(implicit val model:GenerativeModel = defaultGenerativeModel) {
  /** The per-word variable that indicates which topic it comes from. */
  object ZDomain extends DiscreteDomain { def size = numTopics }
  object ZSeqDomain extends DiscreteSeqDomain { def elementDomain = ZDomain }
  class Zs(intValues:Seq[Int]) extends PlatedGateVariable(intValues) {
    def this(len:Int) = this(Seq.fill(len)(0))
    def domain = ZSeqDomain
    //def words: Document = childFactors.first.asInstanceOf[PlatedDiscreteMixture.Factor]._1.asInstanceOf[Document]
  }

  def wordDomain = wordSeqDomain.elementDomain
  /** The prior over per-topic word distribution */
  val betas = new GrowableUniformMasses(wordDomain, beta1)
  /** The prior over per-document topic distribution */
  val alphas = new DenseMasses(numTopics, alpha1)

  /** The collection of all documents used to fit the parameters of this LDA model. */
  private val documentMap = new HashMap[String,Doc] { def +=(d:Document): Unit = this(d.name) = d }
  def documents: Iterable[Doc] = documentMap.values
  def getDocument(name:String) : Doc = documentMap.getOrElse(name, null)
  /** The per-topic distribution over words.  FiniteMixture is a Seq of Dirichlet-distributed Proportions. */
  val phis = Mixture(numTopics)(new GrowableDenseCountsProportions(wordDomain) ~ Dirichlet(betas))
  
  protected def setupDocument(doc:Doc)(implicit m:GenerativeModel = model): Unit = {
    require(wordSeqDomain eq doc.ws.domain)
    require(doc.ws.length > 1)
    if (doc.theta eq null) doc.theta = new SortedSparseCountsProportions(numTopics)
    else require (doc.theta.length == numTopics)
    doc.theta ~ Dirichlet(alphas) // was DenseCountsProportions
    if (doc.zs eq null) doc.zs = new Zs(Array.tabulate(doc.ws.length)(i => random.nextInt(numTopics))) // Could also initialize to all 0 for more efficient sparse inference
    else require(doc.zs.length == doc.ws.length)
    doc.zs ~ PlatedDiscrete(doc.theta)
    doc.ws ~ PlatedDiscreteMixture(phis, doc.zs)
  }

  /** Add a document to the LDA model. */
  def addDocument(doc:Doc): Unit = {
    if (documentMap.contains(doc.name)) throw new Error(this.toString+" already contains document "+doc.name)
    setupDocument(doc)
    documentMap(doc.name) = doc
  }
  
  def removeDocument(doc:Doc): Unit = {
    documentMap.remove(doc.name)
    model match {
      case m:GenerativeFactorModel => {
        m -= m.parentFactor(doc.theta)
        m -= m.parentFactor(doc.zs)
        m -= m.parentFactor(doc.ws)
      }
    }
  }

  /** Infer doc.theta, but to not adjust LDA.phis.  Document not added to LDA model. */
  def inferDocumentThetaOld(doc:Doc, iterations:Int = 10): Unit = {
    var tmp = false
    if (model.parentFactor(doc.ws) eq null) { addDocument(doc); tmp = true }
    val sampler = new CollapsedGibbsSampler(Seq(doc.theta))
    for (i <- 1 to iterations) sampler.process(doc.zs)
    if (tmp) removeDocument(doc)
  }

  def inferDocumentTheta(doc:Doc, iterations:Int = 10): Unit = {
    if (model.parentFactor(doc.ws) ne null) {
      val sampler = new CollapsedGibbsSampler(Seq(doc.theta), model)
      for (i <- 1 to iterations) sampler.process(doc.zs)
    } else {
      val m = new GenerativeFactorModel
      setupDocument(doc)(m)
      val sampler = new CollapsedGibbsSampler(Seq(doc.theta), m)
      for (i <- 1 to iterations) sampler.process(doc.zs)
    }
  }

  /** A batch based version of inferDocumentTheta, not yet complete. */
  def inferMultipleDocumentThetas(newDocs:Seq[Doc], iterations:Int = 10) : Unit = {
    val docsAdded = new HashSet[Doc]
    val varsToSample = new ArrayBuffer[Variable]

    for(doc <- newDocs) if(model.parentFactor(doc.ws) eq null) { addDocument(doc); docsAdded += doc; varsToSample ++= Seq(doc.theta) }
    val sampler = new CollapsedGibbsSampler(varsToSample)
    val startTime = System.currentTimeMillis()
    for(i <- 1 to iterations) {
      for(doc <- newDocs)
        sampler.process(doc.zs)
    }
    println("Total inference time = " + (System.currentTimeMillis() - startTime)/1000.0 + " seconds")

    // removal takes 150 times longer than inference ???
    //val removeStartTime = System.currentTimeMillis()
    //for(doc <- docsAdded) removeDocument(doc)
    //println("Total doc removal time = " + (System.currentTimeMillis() - removeStartTime)/1000.0 + " seconds")
  }

    /** Run a collapsed Gibbs sampler to estimate the parameters of the LDA model. */
  def inferTopics(iterations:Int = 60, diagnosticInterval:Int = 10, fitAlphaInterval:Int = Int.MaxValue): Unit = {
    val sampler = new SparseLDAInferencer(ZDomain, wordDomain, documents, alphas, beta1)
    println("Collapsing finished.  Starting sampling iterations:")
    //sampler.debug = debug
    val startTime = System.currentTimeMillis
    for (i <- 1 to iterations) {
      val startIterationTime = System.currentTimeMillis
      for (doc <- documents) sampler.process(doc.zs.asInstanceOf[Zs])
      val timeSecs = (System.currentTimeMillis - startIterationTime)/1000.0
      if (timeSecs < 2.0) print(".") else print("%.0fsec ".format(timeSecs)); Console.flush
      if (i % diagnosticInterval == 0) {
        println ("\nIteration "+i)
        sampler.export(phis)
        printTopics
      }
      if (i % fitAlphaInterval == 0) {
        sampler.exportThetas(documents)
        DirichletMomentMatching.estimate(alphas)
        sampler.resetSmoothing(alphas, beta1)
        println("alpha = " + alphas.mkString(" "))
      }
    } 
    //println("Finished in "+((System.currentTimeMillis-startTime)/1000.0)+" seconds")
    // Set original uncollapsed parameters to mean of collapsed parameters
    sampler.export(phis)
    sampler.exportThetas(documents)
  }
  
  // Not finished
  def inferTopicsMultithreaded(numThreads:Int, iterations:Int = 60, diagnosticInterval:Int = 10): Unit = {
    val docSubsets = documents.grouped(documents.size/numThreads + 1).toSeq
    //println("Subsets = "+docSubsets.size)
    for (i <- 1 to iterations) {
      docSubsets.par.foreach(docSubset => {
        val sampler = new SparseLDAInferencer(ZDomain, wordDomain, documents, alphas, beta1)
        for (doc <- docSubset) sampler.process(doc.zs.asInstanceOf[Zs])
      })
      if (i % diagnosticInterval == 0) {
        println ("Iteration "+i)
        maximizePhisAndThetas
        printTopics
      }
    }
    maximizePhisAndThetas
  }
  
  def topicWords(topicIndex:Int, numWords:Int = 10): Seq[String] = phis(topicIndex).top(numWords).map(dp => wordDomain.getCategory(dp.index))
  def topicSummary(topicIndex:Int, numWords:Int = 10): String = "Topic "+topicIndex+"  "+(topicWords(topicIndex, numWords).mkString(" ")+"  "+phis(topicIndex).countsTotal.toInt+"  "+alphas(topicIndex))
  def topicsSummary(numWords:Int = 10): String = Range(0, numTopics).map(topicSummary(_)).mkString("\n")

  @deprecated
  def printTopics : Unit = {
    phis.foreach(t => println("Topic " + phis.indexOf(t) + "  " + t.top(10).map(dp => wordDomain.getCategory(dp.index)).mkString(" ")+"  "+t.countsTotal.toInt+"  "+alphas(phis.indexOf(t))))
    println
  }

  def maximizePhisAndThetas: Unit = {
    phis.foreach(_.zero())
    for (doc <- documents; i <- 0 until doc.ws.length) {
      val zi = doc.zs.intValue(i)
      phis(zi).increment(doc.ws.intValue(i), 1.0)(null)
      doc.theta.increment(zi, 1.0)(null)
    }
  }
  
  def saveWordsZs(file:File): Unit = {
    val pw = new PrintWriter(file)
    for (doc <- documents) doc.writeNameWordsZs(pw)
  }
  
  def addDocumentsFromWordZs(file:File): Unit = {
    val reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))
    
  }

  def saveModel(fileName:String) {
    val file = new File(fileName)
    val dir = file.getParentFile()
    if(!dir.exists()) dir.mkdirs()

    val fileWriter = new FileWriter(file);

    fileWriter.write(numTopics + "\n") // what else to write to first line? alpha/beta?  should save wordDomain

    for(doc <- documents) {
      fileWriter.write(doc.name)
      for(i <- 0 until doc.ws.length) fileWriter.write(" " + doc.ws(i) + " " + doc.zs.intValue(i))
      fileWriter.write("\n");
    }

    fileWriter.flush
    fileWriter.close
  }
}


object LDA {
  import scala.collection.mutable.ArrayBuffer
  import scala.util.control.Breaks._
  var verbose = false
  val minDocLength = 3
  def main(args:Array[String]): Unit = {
    object opts extends cc.factorie.util.DefaultCmdOptions {
      val numTopics =     new CmdOption("num-topics", 't', 10, "N", "Number of topics.")
      val alpha =         new CmdOption("alpha", 0.1, "N", "Dirichlet parameter for per-document topic proportions.")
      val beta =          new CmdOption("beta", 0.1, "N", "Dirichlet parameter for per-topic word proportions.")
      val numThreads =    new CmdOption("num-threads", 1, "N", "Number of threads for multithreaded topic inference.")
      val numIterations = new CmdOption("num-iterations", 'i', 50, "N", "Number of iterations of inference.")
      val diagnostic =    new CmdOption("diagnostic-interval", 'd', 10, "N", "Number of iterations between each diagnostic printing of intermediate results.")
      val tokenRegex =    new CmdOption("token-regex", "\\p{Alpha}+", "REGEX", "Regular expression for segmenting tokens.")
      val readDirs =      new CmdOption("read-dirs", List(""), "DIR...", "Space-(or comma)-separated list of directories containing plain text input files.")
      val readLines =     new CmdOption("read-lines", "", "FILENAME", "File containing lines of text, one for each document.")
      val readLinesRegex= new CmdOption("read-lines-regex", "", "REGEX", "Regular expression with parens around the portion of the line that should be read as the text of the document.")
      val readLinesRegexGroups= new CmdOption("read-lines-regex-groups", List(1), "GROUPNUMS", "The --read-lines-regex group numbers from which to grab the text of the document.")
      val readLinesRegexPrint = new CmdOption("read-lines-regex-print", false, "BOOL", "Print the --read-lines-regex match that will become the text of the document.")
      val readDocs =      new CmdOption("read-docs", "lda-docs.txt", "FILENAME", "Add documents from filename , reading document names, words and z assignments") 
      val writeDocs =     new CmdOption("write-docs", "lda-docs.txt", "FILENAME", "Save LDA state, writing document names, words and z assignments") 
      val maxNumDocs =    new CmdOption("max-num-docs", Int.MaxValue, "N", "The maximum number of documents to read.")
      val printTopics =   new CmdOption("print-topics", 20, "N", "Just before exiting print top N words for each topic.")
      val verbose =       new CmdOption("verbose", "Turn on verbose output") { override def invoke = LDA.this.verbose = true }
    }
    opts.parse(args)
    /** The domain of the words in documents */
    object WordSeqDomain extends CategoricalSeqDomain[String]
    val lda = new LDA(WordSeqDomain, opts.numTopics.value, opts.alpha.value, opts.beta.value)
    if (opts.readDirs.wasInvoked) {
      for (directory <- opts.readDirs.value) {
        val dir = new File(directory); if (!dir.isDirectory) { System.err.println(directory+" is not a directory."); System.exit(-1) }
        println("Reading files from directory " + directory)
        breakable { for (file <- new File(directory).listFiles; if (file.isFile)) {
          if (lda.documentMap.size == opts.maxNumDocs.value) break
          val doc = Document(WordSeqDomain, file, "UTF-8")
          if (doc.length >= minDocLength) lda.addDocument(doc)
          if (lda.documentMap.size % 1000 == 0) { print(" "+lda.documentMap.size); Console.flush() }; if (lda.documentMap.size % 10000 == 0) println()
        }}
        //println()
      }
    } 
    if (opts.readLines.wasInvoked) {
      val name = if (opts.readLines.value == "-") "stdin" else opts.readLines.value
      val source = if (opts.readLines.value == "-") scala.io.Source.stdin else scala.io.Source.fromFile(new File(opts.readLines.value))
      var count = 0
      breakable { for (line <- source.getLines()) {
        if (lda.documentMap.size == opts.maxNumDocs.value) break
        val text: String = 
          if (!opts.readLinesRegex.wasInvoked) line 
          else {
            val textbuffer = new StringBuffer
            for (groupIndex <- opts.readLinesRegexGroups.value) {
            	val mi = opts.readLinesRegex.value.r.findFirstMatchIn(line).getOrElse(throw new Error("No regex match for --read-lines-regex in "+line))
            	if (mi.groupCount >= groupIndex) textbuffer append mi.group(groupIndex)
            	else throw new Error("No group found with index "+groupIndex)
            }
            textbuffer.toString
          }
        if (text eq null) throw new Error("No () group for --read-lines-regex in "+line)
        if (opts.readLinesRegexPrint.value) println(text)
        val doc = Document(WordSeqDomain, name+":"+count, opts.tokenRegex.value.r.findAllIn(text).map(_.toLowerCase).filter(!Stopwords.contains(_)))
        if (doc.length >= minDocLength) lda.addDocument(doc)
        count += 1
        if (count % 1000 == 0) { print(" "+count); Console.flush() }; if (count % 10000 == 0) println()
      }}
      source.close()
    }
    if (opts.readDocs.wasInvoked) {
      val file = new File(opts.readDocs.value)
      val reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))
      breakable { while (true) {
        if (lda.documentMap.size == opts.maxNumDocs.value) break
        val doc = new Document(WordSeqDomain, "", Nil)
        doc.zs = new lda.Zs(Nil)
        val numWords = doc.readNameWordsZs(reader)
        if (numWords < 0) break
        else if (numWords >= minDocLength) lda.addDocument(doc) // Skip documents that have only one word because inference can't handle them
        else System.err.println("--read-docs skipping document %s: only %d words found.".format(doc.name, numWords))
      }}
      reader.close()
      lda.maximizePhisAndThetas
      //println(lda.documents.head.ws.categoryValues.mkString(" "))
      //println(lda.documents.head.zs.intValues.mkString(" "))
    }
    if (lda.documents.size == 0) { System.err.println("You must specific either the --input-dirs or --input-lines options to provide documents."); System.exit(-1) }
    println("\nRead "+lda.documents.size+" documents, "+WordSeqDomain.elementDomain.size+" word types, "+lda.documents.map(_.ws.length).sum+" word tokens.")
    
    // Run inference to discover topics
    if (opts.numIterations.value > 0) {
      val startTime = System.currentTimeMillis
      if (opts.numThreads.value > 1) 
       lda.inferTopicsMultithreaded(opts.numThreads.value, opts.numIterations.value, opts.diagnostic.value) 
      else 
        lda.inferTopics(opts.numIterations.value, opts.diagnostic.value)
      println("Finished in " + ((System.currentTimeMillis - startTime) / 1000.0) + " seconds")
  	}	

    //testSaveLoad(lda)
    
    if (opts.writeDocs.wasInvoked) {
      val file = new File(opts.writeDocs.value)
      val pw = new PrintWriter(file)
      lda.documents.foreach(_.writeNameWordsZs(pw))
      pw.close()
    }
    
    if (opts.printTopics.wasInvoked) 
      println(lda.topicsSummary(opts.printTopics.value))

  }

  @deprecated("Will be removed")
  def loadModel(fileName:String, wordSeqDomain:CategoricalSeqDomain[String] = new CategoricalSeqDomain[String]) : LDA = {
    val file = new File(fileName)
    if(!file.exists()) return null;

    val source = scala.io.Source.fromFile(file)
    val lines = source.getLines()
    if(!lines.hasNext) new Error("File " + fileName + " had 0 lines")

    val startTime = System.currentTimeMillis()

    var line = lines.next()
    val numTopics = java.lang.Integer.parseInt(line.trim()) // first line has non-document details

    println("loading model with " + numTopics + " topics")

    val lda = new LDA(wordSeqDomain, numTopics) // do we have to create this here?  problem because we don't have topics/alphas/betas/etc beforehand to create LDA instance
    while(lines.hasNext) {
      line = lines.next()
      var tokens = new ArrayBuffer[String]
      var topicAssignments = new ArrayBuffer[Int]
      val fields = line.split(" +")

      assert(fields.length >= 3) // at least 1 token

      val docName = fields(0)
      for(i <- 1 until fields.length by 2) { // grab each pair of token/count
        tokens += fields(i)
        topicAssignments += java.lang.Integer.parseInt(fields(i+1))
      }

      val doc = Document(wordSeqDomain, docName, tokens.iterator) // create and add document
      lda.addDocument(doc)
      for(i <- 0 until doc.length) // put z's to correct values we found in loaded file
        doc.zs.set(i, topicAssignments(i))(null)

      //for(i <- 0 until doc.length) Console.print(doc(i) + " " + doc.zs.intValue(i) + " ")
      //Console.println("");
    }

    lda.maximizePhisAndThetas

    println("Load file time = " + (System.currentTimeMillis() - startTime)/1000.0 + " seconds")

    return lda
  }

  def testSaveLoad(lda:LDA) {
    val testLoc = "/Users/kschultz/dev/backedup/models/ldatestsave/ldatestsave"
    lda.saveModel(testLoc)

    val testLoad = loadModel(testLoc)
    val testLoadSameDomain = loadModel(testLoc, lda.wordSeqDomain)

    Console.println("Topics from pre-save model: \n")
    lda.printTopics; //debugTopics(lda)

    Console.println("**********************************\n")

    Console.println("Topics from loaded model (SAME WordSeqDomain): \n")
    testLoadSameDomain.printTopics; // debugTopics(testLoadSameDomain)

    Console.println("**********************************\n")

    Console.println("Topics from loaded model (NEW WordSeqDomain): \n")
    testLoad.printTopics; // debugTopics(testLoad)
    
    verifyPhis(lda, testLoad)
  }

  def verifyPhis(lda1:LDA, lda2:LDA) : Unit = {
    val topicPlusWordToCountMap = new HashMap[String, Double]
    
    for(t <- lda1.phis) {
      val topicId = lda1.phis.indexOf(t)
      for(i <- 0 until t.length) {
        val word = lda1.wordDomain.getCategory(i)
        val count = t.counts(i)
        topicPlusWordToCountMap(topicId + "_" + word) = count
      }
    }


    for(t2 <- lda2.phis) {
      val topicId = lda2.phis.indexOf(t2)
      for(i <- 0 until t2.length) {
        val word = lda2.wordDomain.getCategory(i)
        val count = t2.counts(i)
        if(topicPlusWordToCountMap(topicId + "_" + word) != count) Console.err.println("failed to match count for topic " + topicId + " and word " + word + " with count " + count)
      }
    }
  }

  def debugTopics(lda:LDA) {
    lda.phis.foreach(t => {
      print("Topic " + lda.phis.indexOf(t) + "  ");
      t.top(20).zipWithIndex.foreach(dp => print(dp._2 + "=" + lda.wordDomain.getCategory(dp._1.index) + "(" + t.counts(dp._1.index) + ", " + dp._1.index + ") "));
      println
    })
    println
  }
}
