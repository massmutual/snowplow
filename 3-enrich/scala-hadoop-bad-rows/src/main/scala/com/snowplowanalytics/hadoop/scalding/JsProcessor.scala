/*
 * Copyright (c) 2016 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.hadoop.scalding

import org.mozilla.javascript._

import scala.util.control.NonFatal

object JsProcessor {

  object Variables {
    private val prefix = "$snowplow31337" // To avoid collisions
    val InTsv  = s"${prefix}InTsv"
    val InErrors  = s"${prefix}InErrors"
    val Out = s"${prefix}Out"
  }

  /**
   * Create a script containing the user-defined "process" function,
   * some helper functions, and a call to the "process" function
   *
   * @param sourceCode The JavaScript provided by the user
   * @return Compiled script
   */
  def compile(sourceCode: String): Script = {
    val wholeScript =
      s"""|// Helper functions
          |function tsvToArray(event) {
          |  return event.split('\t', -1);
          |}
          |function arrayToTsv(tsv) {
          |  return tsv.join('\t');
          |}
          |
          |// User-supplied script
          |${sourceCode}
          |
          |// Immediately invoke using reserved args
          |var ${Variables.Out} = process(${Variables.InTsv}, ${Variables.InErrors});
          |
          |// Don't return anything
          |null;
          |""".stripMargin

    val cx = Context.enter()
    try {
      cx.compileString(wholeScript, "user-defined-script", 0, null)
    } finally {
      Context.exit()
    }    
  }
}

/**
 * Class to process bad events using user-defined custom JavaScript
 *
 * @param sourceCode
 */
class JsProcessor(sourceCode: String) extends TsvProcessor {

  val compiledScript: Script = JsProcessor.compile(sourceCode)

  /**
   * Call the user-defined process function, passing the bad row's original TSV and array of errors
   *
   * @param script
   * @param event Raw event TSV
   * @param errors Array of errors extracted from bad row
   * @return None if the bad row should be ignored, other wise Some(mutateInputTsv)
   */
  def applyToTsv(script: Script, event: String, errors: Seq[String]): Option[String] = {
    val cx = Context.enter()
    val scope = cx.initStandardObjects
    try {
      scope.put(JsProcessor.Variables.InTsv, scope, Context.javaToJS(event, scope))
      scope.put(JsProcessor.Variables.InErrors, scope, Context.javaToJS(errors.toArray, scope))
      val retVal = script.exec(cx, scope)
    } catch {
      case NonFatal(nf) => {
        nf.printStackTrace()
      } // TODO
    } finally {
      Context.exit()
    }

    Option(scope.get(JsProcessor.Variables.Out)) match {
      case None => None
      case Some(obj) => {
        try {
          Some(obj.asInstanceOf[String])
        } catch {
          case NonFatal(nf) => {
            nf.printStackTrace()
            None
          } // TODO
        }
      }
    }
  }
  
  /**
   * Decide whether to try to fix up a given bad row, then act accordingly
   *
   * @param inputTsv The tab-separated raw event in the Cloudfront Access Log format
   * @param errors An array of errors describing why the inputTsv is invalid
   * @return Some(mutatedInputTsv), or None if this bad row should be ignored
   */
  def process(inputTsv: String, errors: Seq[String]): Option[String] =
    applyToTsv(compiledScript, inputTsv, errors)
}
